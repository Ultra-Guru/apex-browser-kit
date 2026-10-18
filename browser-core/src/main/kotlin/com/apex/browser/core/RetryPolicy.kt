package com.apex.browser.core

import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.delay

/**
 * 浏览器原子操作的错误恢复策略（P1 #7）。
 *
 * 生产级 Agent 不能「无限重试同一操作」也不能「一失败就放弃」。采用
 * 指数退避 + 最大重试 + 熔断器的组合：连续多次失败后暂停一段时间，避免被限流或雪崩。
 *
 * 设计约束（错误语义两分法）：
 * - **瞬态错误**（仅 [retryableExceptions] 中的异常：元素未找到、超时、WebView 无响应）
 *   才退避重试 —— 页面可能只是没渲染完，下一轮重试有意义。
 * - **语义错误不可重试**：[HandoffLockedException]（人工接管锁）与
 *   [PermanentActionException]（操作本身不可能成功，v1.3.0 新增）直接上抛，
 *   不烧退避时间、不计入熔断计数 —— 重试一个必然再失败的操作只会拖慢会话
 *   并把熔断器误打向 OPEN，一个坏参数锁死整段会话。
 */
data class RetryPolicy(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 500,
    val backoffMultiplier: Double = 2.0,
    val maxDelayMs: Long = 5000,
    val retryableExceptions: Set<Class<out Throwable>> = setOf(
        ElementNotFoundException::class.java,
        java.util.concurrent.TimeoutException::class.java,
        WebViewNotRespondingException::class.java,
    ),
)

/** 元素在 DOM 中找不到（ref 失效或页面未渲染完） */
class ElementNotFoundException(message: String) : Exception(message)

/** WebView 在规定时间内未通过 evaluateJavascript 回调（可能主线程卡死） */
class WebViewNotRespondingException(message: String) : Exception(message)

/**
 * **永久性语义错误**（v1.3.0）：操作本身不可能成功，重试永远无效。
 *
 * 与瞬态错误（[ElementNotFoundException] / [TimeoutException] /
 * [WebViewNotRespondingException]——页面可能未就绪，重试有意义）相对，本异常
 * 标记的是「参数与页面现实不匹配」这类确定性失败：
 * - 对非可输入元素 inputText（如对 <div> 写值）；
 * - selectOption 的选项不匹配（<select> 里根本没有请求的 value/text）；
 * - 输入结果回传解析失败（页面侧脚本被改形，预期内无法自愈）。
 *
 * [withRetry] 对本异常**立即上抛**：不退避、不计熔断 —— 重试同一组参数只会
 * 烧掉 3 次退避时长并把熔断器推向 OPEN，让一个坏调用拖垮后续所有动作。
 */
class PermanentActionException(message: String) : Exception(message)

/**
 * 熔断器：连续失败达到阈值后进入 OPEN 状态，暂停执行一段时间（[resetTimeoutMs]），
 * 之后进入 HALF_OPEN 试探一次，成功则回 CLOSED。
 *
 * v1.3.0 并发修复：[acquire] / [onSuccess] / [onFailure] 全部 @Synchronized ——
 * 旧实现只把 state 标 @Volatile，但两个关键路径都是**读-改-写**复合操作：
 * - `consecutiveFailures++` 非原子，两个线程交错自增会丢失计数，连续失败数
 *   被低估 → 熔断迟迟不 OPEN，雪崩保护失效；
 * - acquire 的 OPEN→HALF_OPEN 转换与 onFailure 的阈值判断交错时，可能出现
 *   HALF_OPEN 探测与计数残留的不一致状态。
 *
 * Agent 工具在 Dispatchers.IO 上并发调用、WebView 回调在主线程触发 onSuccess ——
 * 跨线程并发是常态而非理论场景。currentState 读取保持无锁（@Volatile 读），
 * 写入全部经同步块串行化。
 */
class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val resetTimeoutMs: Long = 30_000,
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    @Volatile private var state: State = State.CLOSED
    private var consecutiveFailures = 0
    private var openedAt = 0L

    val currentState: State get() = state

    /** 执行前检查：OPEN 且未到重置时间则抛 [CircuitOpenException]（窗口过后转 HALF_OPEN 放行试探） */
    @Synchronized
    fun acquire() {
        val now = System.currentTimeMillis()
        when (state) {
            State.OPEN -> {
                if (now - openedAt >= resetTimeoutMs) {
                    state = State.HALF_OPEN
                } else {
                    throw CircuitOpenException("浏览器操作熔断器开启中（已连续失败 $failureThreshold 次），${resetTimeoutMs / 1000}s 后重试")
                }
            }
            else -> Unit
        }
    }

    @Synchronized
    fun onSuccess() {
        consecutiveFailures = 0
        state = State.CLOSED
    }

    @Synchronized
    fun onFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= failureThreshold) {
            state = State.OPEN
            openedAt = System.currentTimeMillis()
        }
    }
}

class CircuitOpenException(message: String) : Exception(message)

/**
 * 同步执行带重试+熔断的区块（在调用方协程上下文内运行）。
 * - 不可重试异常（非 [RetryPolicy.retryableExceptions]，含 [PermanentActionException]
 *   语义错误）立即向上抛出，不计入熔断。
 * - 熔断开启时立即抛 [CircuitOpenException]，不进入退避。
 */
suspend fun <T> withRetry(
    policy: RetryPolicy,
    breaker: CircuitBreaker,
    block: suspend () -> T,
): T {
    breaker.acquire()
    var lastErr: Throwable? = null
    repeat(policy.maxRetries + 1) { attempt ->
        try {
            val result = block()
            breaker.onSuccess()
            return result
        } catch (e: Throwable) {
            lastErr = e
            // 不可重试异常：直接抛，不计入熔断
            if (policy.retryableExceptions.none { it.isInstance(e) } && e !is CircuitOpenException) {
                throw e
            }
            breaker.onFailure()
            if (attempt < policy.maxRetries) {
                val delayMs = min(policy.maxDelayMs, (policy.initialDelayMs * policy.backoffMultiplier.pow(attempt)).toLong())
                delay(delayMs)
            }
        }
    }
    throw lastErr ?: IllegalStateException("重试失败")
}
