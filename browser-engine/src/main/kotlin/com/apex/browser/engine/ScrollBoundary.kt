package com.apex.browser.engine

import com.apex.browser.core.BrowserScript
import kotlinx.coroutines.delay

/**
 * 滚动到页面边界（v1.8.0）——[BrowserEngine] 的扩展函数（GATE 1 文件预算，
 * 模式同 EngineCapture.kt；仅依赖引擎公开面 activeTab / executeJavaScript）。
 *
 * [toBottom] = true 滚到底部（无限滚动触发新一屏加载、页脚确认），
 * false 回顶部。与 [BrowserEngine.scroll] 的 deltaY 模式互补：后者受
 * [BrowserScript.MAX_SCROLL_PX] 单次上限约束，超长页面到底需多次调用；
 * 本方法一步到位。滚动后留一个短稳定窗（懒加载触发），不等待新内容
 * （需要时配 waitForCondition）。
 */
suspend fun BrowserEngine.scrollToBoundary(toBottom: Boolean): Boolean {
    if (activeTab() == null) return false
    // executeJavaScript 回传 "true"/"false"（脚本同步返回布尔）
    val ok = executeJavaScript(BrowserScript.scrollToBoundaryJs(toBottom)) == "true"
    if (ok) delay(150) // 留出惯性滚动/懒加载触发窗
    return ok
}
