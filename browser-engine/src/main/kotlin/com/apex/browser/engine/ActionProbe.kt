package com.apex.browser.engine

import android.os.SystemClock
import android.webkit.WebView
import com.apex.browser.core.BrowserScript
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 动作前后探针 + 自适应等待子系统（v1.8.0 自 BrowserEngine.kt 拆出——GATE 1
 * 文件预算，模式同 WaitPredicates.kt / EngineTracing.kt）。
 *
 * 探针（[readQuickProbe]）是一次轻量 evaluateJavascript，读 URL / 标题 /
 * 交互元素数 / 滚动位；动作前后各读一次即可计算「点击是否产生可观察效果」
 * （[probePage]）。v1.8.0 提速把动作后的**固定延迟**替换为 [awaitActionSettle]
 * 的「探针轮询早退」：页面已变化即返回，不再睡满固定预算。
 *
 * 可见性：internal（仅引擎同包消费，不入公开 API 基线）。依赖
 * [BrowserEngine.evaluateJson]（v1.8.0 起同 internal 可见性）。
 */
internal class QuickProbe(
    val url: String,
    val title: String,
    val interactiveCount: Int,
    val scrollY: Int,
)

/** 页面快照探针（动作前后各读一次）：URL / 标题 / 可交互元素数 / 滚动位。 */
internal suspend fun BrowserEngine.readQuickProbe(wv: WebView): QuickProbe? {
    val jsonStr = runCatching { evaluateJson(wv, BrowserScript.POST_ACTION_PROBE_JS) }.getOrNull() ?: return null
    return runCatching {
        // 本地实例（与引擎 json 配置同口径；引擎私有成员不可见于扩展）
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        val obj = json.parseToJsonElement(jsonStr).jsonObject
        QuickProbe(
            url = obj["url"]?.jsonPrimitive?.content ?: "",
            title = obj["title"]?.jsonPrimitive?.content ?: "",
            interactiveCount = obj["interactiveCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            scrollY = obj["scrollY"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        )
    }.getOrNull()
}

/**
 * 动作后探针（v1.1.0 真实 diff）：与动作前 [before] 对比计算 URL 变化与
 * 元素增量。旧实现 urlChanged 恒 false、newElementsCount 是当前计数
 * 而非增量——Agent 拿不到「点击是否产生效果」的可靠证据。
 */
internal suspend fun BrowserEngine.probePage(wv: WebView, before: QuickProbe? = null): BrowserEngine.PostActionState {
    val after = readQuickProbe(wv)
        ?: return BrowserEngine.PostActionState.failed("探针执行失败")
    return diffProbe(after, before)
}

/**
 * 动作后自适应等待（v1.8.0 提速）：固定延迟改为「探针轮询早退」。
 *
 * 旧实现点击后固定 `delay(300~800ms 随机)` 再探针——链接点击几百毫秒
 * 就完成跳转/渲染的场景，也要睡满随机值。现改为：等 [minMs] 后首查探针，
 * 页面已相对 [before] 变化（URL/标题/交互元素数）即立即返回；否则每
 * 100ms 复查，至 [maxMs] 兜底返回。典型点击（内容秒变）从平均 ~550ms
 * 降到 ~140ms；页面无变化的动作（toggle 类）仍以 [maxMs] 封顶，不劣于
 * 旧随机上界。探针本身是一次毫秒级 evaluateJavascript，轮询开销可忽略。
 */
internal suspend fun BrowserEngine.awaitActionSettle(
    wv: WebView,
    before: QuickProbe?,
    minMs: Long,
    maxMs: Long,
): BrowserEngine.PostActionState {
    val start = SystemClock.uptimeMillis()
    if (minMs > 0) delay(minMs)
    var after = readQuickProbe(wv) ?: return probePage(wv, before)
    while (before != null && !probeChanged(before, after) &&
        SystemClock.uptimeMillis() - start < maxMs
    ) {
        delay(100)
        after = readQuickProbe(wv) ?: break
    }
    return diffProbe(after, before)
}

/** 两探针间是否有可观察变化（URL / 标题 / 交互元素数）。 */
internal fun probeChanged(a: QuickProbe, b: QuickProbe): Boolean =
    a.url != b.url || a.title != b.title || a.interactiveCount != b.interactiveCount

/** 探针 → 动作后验证状态（diff 计算单一出口：probePage / awaitActionSettle 同口径）。 */
private fun diffProbe(after: QuickProbe, before: QuickProbe?): BrowserEngine.PostActionState =
    BrowserEngine.PostActionState(
        success = true,
        urlChanged = before != null && before.url != after.url,
        newElementsCount = if (before != null) (after.interactiveCount - before.interactiveCount).coerceAtLeast(0) else after.interactiveCount,
        pageTitle = after.title,
        scrollY = after.scrollY,
        currentUrl = after.url,
    )
