package com.apex.browser.engine

import android.os.SystemClock

/**
 * 动作边界 tracer 包装（v1.3.0，internal）：成功记 "ok"、失败记 "failed" + error
 * 摘要（工具名、参数、耗时、会话状态、URL），异常原样上抛 —— 观测不改变语义。
 *
 * 独立成文件而非引擎私有方法的原因：
 * - BrowserEngine.kt 已贴近非注释行预算（1200，check_code_quality GATE 1），
 *   观测横切关注点外移为同包 internal 扩展，引擎文件只保留动作语义本体；
 * - 仅依赖引擎**公开**成员（[BrowserEngine.tracer] / [BrowserEngine.activeTab] /
 *   [BrowserEngine.currentState]），对消费方不可见（internal，不入 API 基线）。
 */
internal suspend fun <T> BrowserEngine.tracedAction(tool: String, params: String, block: suspend () -> T): T {
    val start = SystemClock.uptimeMillis()
    try {
        return block().also {
            tracer.record(tool, params, "ok", SystemClock.uptimeMillis() - start, activeTab()?.url, currentState.name)
        }
    } catch (e: Throwable) {
        tracer.record(tool, params, "failed", SystemClock.uptimeMillis() - start, activeTab()?.url, currentState.name, e.message?.take(120))
        throw e
    }
}
