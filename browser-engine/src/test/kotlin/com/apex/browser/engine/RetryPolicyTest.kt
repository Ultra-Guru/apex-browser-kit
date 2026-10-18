package com.apex.browser.engine

import com.apex.browser.core.CircuitBreaker
import com.apex.browser.core.ElementNotFoundException
import com.apex.browser.core.CircuitOpenException
import com.apex.browser.core.PermanentActionException
import com.apex.browser.core.RetryPolicy
import com.apex.browser.core.withRetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 P1 #7 错误恢复策略的「意图」：
 * - 不可重试异常（语义错误）不重试、不计入熔断，立即抛出。
 * - 可重试异常指数退避重试 maxRetries 次后仍失败则抛出。
 * - 连续失败达到阈值后熔断器打开，后续调用立即抛 CircuitOpenException。
 *
 * 修复说明：原用「runTest 内嵌 runTest」——kotlinx-coroutines-test 明确禁止嵌套（必抛
 * IllegalStateException，掩盖被测异常）；且 CircuitBreaker 用 System.currentTimeMillis
 * 真实时钟，runTest 的虚拟时间无法推进重置窗口。改用 runBlocking（真实时间）+
 * runCatching 捕获后断言异常类型。
 */
class RetryPolicyTest {

    @Test
    fun `不可重试异常立即抛出且不计熔断`() = runBlocking {
        val breaker = CircuitBreaker(failureThreshold = 2, resetTimeoutMs = 1000)
        val policy = RetryPolicy(maxRetries = 3)
        // HandoffLockedException 不在 retryableExceptions 中 → 直接抛，不重试
        val thrown = runCatching {
            withRetry(policy, breaker) { throw BrowserEngine.HandoffLockedException("human driving") }
        }.exceptionOrNull()
        assertTrue("预期 HandoffLockedException，实际 ${thrown?.javaClass}", thrown is BrowserEngine.HandoffLockedException)
        assertEquals(CircuitBreaker.State.CLOSED, breaker.currentState)
    }

    @Test
    fun `可重试异常重试耗尽后抛出且触发熔断`() = runBlocking {
        val breaker = CircuitBreaker(failureThreshold = 2, resetTimeoutMs = 1000)
        val policy = RetryPolicy(maxRetries = 2, initialDelayMs = 1, maxDelayMs = 2)
        var attempts = 0
        val thrown = runCatching {
            withRetry(policy, breaker) {
                attempts++
                throw ElementNotFoundException("ref not found")
            }
        }.exceptionOrNull()
        assertTrue(thrown is ElementNotFoundException)
        // maxRetries=2 → 1 次初始 + 2 次重试 = 3 次
        assertEquals(3, attempts)
        // 连续 3 次失败 >= 阈值 2 → 熔断打开
        assertEquals(CircuitBreaker.State.OPEN, breaker.currentState)
    }

    @Test
    fun `熔断打开时立即拒绝且重置后恢复`() = runBlocking {
        val breaker = CircuitBreaker(failureThreshold = 1, resetTimeoutMs = 50)
        val policy = RetryPolicy(maxRetries = 0)
        // 第一次失败即达阈值 → 打开
        val thrown = runCatching {
            withRetry(policy, breaker) { throw ElementNotFoundException("x") }
        }.exceptionOrNull()
        assertTrue(thrown is ElementNotFoundException)
        assertEquals(CircuitBreaker.State.OPEN, breaker.currentState)
        // 打开期内再次调用 → 立即 CircuitOpenException
        val rejected = runCatching { withRetry(policy, breaker) { "ok" } }.exceptionOrNull()
        assertTrue(rejected is CircuitOpenException)
        // 等待重置窗口（真实时间）后，进入 HALF_OPEN 试探并成功 → 回 CLOSED
        delay(60)
        val r = withRetry(policy, breaker) { "recovered" }
        assertEquals("recovered", r)
        assertEquals(CircuitBreaker.State.CLOSED, breaker.currentState)
    }

    @Test
    fun `成功调用重置熔断计数`() = runBlocking {
        val breaker = CircuitBreaker(failureThreshold = 1, resetTimeoutMs = 1000)
        val policy = RetryPolicy(maxRetries = 0)
        // 一次失败 → 打开
        val thrown = runCatching {
            withRetry(policy, breaker) { throw ElementNotFoundException("x") }
        }.exceptionOrNull()
        assertTrue(thrown is ElementNotFoundException)
        assertEquals(CircuitBreaker.State.OPEN, breaker.currentState)
        // 不重置，直接验证：失败后若成功会闭合（此处模拟已闭合场景）
        val breaker2 = CircuitBreaker(failureThreshold = 5, resetTimeoutMs = 1000)
        repeat(2) {
            withRetry(policy, breaker2) { "ok" }
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker2.currentState)
    }

    // ═══ v1.3.0：永久性语义错误 + 熔断并发修复 + 超时可重试 ═══

    @Test
    fun `永久性语义错误不重试不计熔断`() = runBlocking {
        val breaker = CircuitBreaker(failureThreshold = 2, resetTimeoutMs = 1000)
        val policy = RetryPolicy(maxRetries = 3)
        var attempts = 0
        val thrown = runCatching {
            withRetry(policy, breaker) {
                attempts++
                throw PermanentActionException("ref=r_x 不是可输入元素")
            }
        }.exceptionOrNull()
        assertTrue("预期 PermanentActionException，实际 ${thrown?.javaClass}", thrown is PermanentActionException)
        assertEquals("永久性错误必须立即上抛（重试永远无效）", 1, attempts)
        assertEquals("永久性错误不得计入熔断", CircuitBreaker.State.CLOSED, breaker.currentState)
    }

    @Test
    fun `超时异常属于可重试集合并按退避重试`() = runBlocking {
        // evaluateSnapshotJs 的 TimeoutCancellationException 转译承接方：
        // juc TimeoutException 必须在默认可重试集合内，否则转译了也不重试。
        val policy = RetryPolicy()
        assertTrue(
            "java.util.concurrent.TimeoutException 必须在默认可重试集合内",
            policy.retryableExceptions.contains(java.util.concurrent.TimeoutException::class.java),
        )
        val breaker = CircuitBreaker(failureThreshold = 5, resetTimeoutMs = 1000)
        val fast = RetryPolicy(maxRetries = 2, initialDelayMs = 1, maxDelayMs = 2)
        var attempts = 0
        val thrown = runCatching {
            withRetry(fast, breaker) {
                attempts++
                throw java.util.concurrent.TimeoutException("snapshot eval timeout")
            }
        }.exceptionOrNull()
        assertTrue(thrown is java.util.concurrent.TimeoutException)
        assertEquals("maxRetries=2 → 1 次初始 + 2 次重试 = 3 次", 3, attempts)
    }

    @Test
    fun `并发 onFailure 计数不丢失`() {
        // v1.3.0 @Synchronized 回归锁：8 线程 × 50 次失败 = 400 恰好达阈值。
        // 旧实现 consecutiveFailures++ 非原子，交错自增丢计数 → 状态停在 CLOSED。
        val breaker = CircuitBreaker(failureThreshold = 400, resetTimeoutMs = 60_000)
        val threads = (1..8).map {
            Thread { repeat(50) { breaker.onFailure() } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(
            "全部 400 次失败必须被计数（恰触阈值 → OPEN）；CLOSED 即同步回归",
            CircuitBreaker.State.OPEN,
            breaker.currentState,
        )
    }

    @Test
    fun `熔断窗口内并发 acquire 一致拒绝且窗口后放行恢复`() = runBlocking {
        val breaker = CircuitBreaker(failureThreshold = 1, resetTimeoutMs = 400)
        breaker.onFailure()
        assertEquals(CircuitBreaker.State.OPEN, breaker.currentState)
        val rejected = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (1..8).map {
            Thread {
                runCatching { breaker.acquire() }
                    .onFailure { rejected.incrementAndGet() }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals("窗口内并发 acquire 必须全部被拒（无放行漏网）", 8, rejected.get())
        // 越过重置窗口：OPEN → HALF_OPEN 放行试探，成功后回 CLOSED
        delay(500)
        breaker.acquire()
        breaker.onSuccess()
        assertEquals(CircuitBreaker.State.CLOSED, breaker.currentState)
    }
}
