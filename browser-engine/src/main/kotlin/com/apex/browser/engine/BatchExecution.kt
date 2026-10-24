package com.apex.browser.engine

import com.apex.browser.core.BatchAction
import com.apex.browser.core.BatchActionValidator

/**
 * 批量动作执行（v1.8.0，自 BrowserEngine.kt 拆出——GATE 1 文件预算纪律，
 * 模式同 WaitPredicates.kt：独立能力独立文件，扩展函数形态保持引擎
 * 调用点语法不变）。
 *
 * 一次调用串行执行多个动作，把 N 次「模型往返 → 工具执行」压缩为 1 次——
 * 表单填写（输入 → 选择 → 勾选 → 提交）这类序列已确定的场景是主战场。
 *
 * 语义：
 * - 序列先经 [BatchActionValidator.check]（空序列 / 超上限 / 字段缺失直接抛
 *   [IllegalArgumentException]，由宿主 SafeAgentTool 转错误文本）；
 * - 逐个动作委托既有单动作路径（各自带重试/熔断/模糊自愈/动作后验证，
 *   trace 逐条入 tracer），WAIT 动作走 waitForCondition 七模式；
 * - **任一动作失败即停止**：失败上下文对下一步决策有意义，盲跑到底会把
 *   页面带到未知状态；已完成的动作照常返回。
 */
suspend fun BrowserEngine.batchExecute(actions: List<BatchAction>): List<BatchOutcome> =
    tracedAction("browser_batch", "n=${actions.size}") {
        BatchActionValidator.check(actions)?.let { throw IllegalArgumentException(it) }
        val out = mutableListOf<BatchOutcome>()
        for ((i, a) in actions.withIndex()) {
            val st = when (a.type) {
                BatchAction.Type.CLICK -> clickElement(a.ref)
                BatchAction.Type.INPUT -> inputText(a.ref, a.text, a.append, a.pressEnter)
                BatchAction.Type.SELECT -> selectOption(a.ref, a.value, a.byText)
                BatchAction.Type.PRESS_KEY -> pressKey(a.key)
                BatchAction.Type.SCROLL -> {
                    // scroll 内部自带「无激活标签页 → scrolled=false」语义
                    val r = scroll(a.deltaY).scrolled
                    BrowserEngine.PostActionState(success = r, failReason = if (r) null else "滚动失败（无激活标签页）")
                }
                BatchAction.Type.WAIT -> {
                    val o = waitForCondition(a.waitMode, a.waitValue, a.waitTimeoutMs)
                    BrowserEngine.PostActionState(success = o.matched, failReason = if (o.matched) null else o.detail)
                }
            }
            out += BatchOutcome(i, a, st)
            if (!st.success) break
        }
        out
    }

/** [batchExecute] 的单动作结果：[index] 为提交序列中的位置（0 起）。 */
data class BatchOutcome(
    val index: Int,
    val action: BatchAction,
    val state: BrowserEngine.PostActionState,
) {
    /** 面向模型的单行摘要（工具层直接拼接返回）。 */
    fun summaryLine(): String {
        val label = when (action.type) {
            BatchAction.Type.CLICK -> "click ${action.ref}"
            BatchAction.Type.INPUT -> "input ${action.ref}"
            BatchAction.Type.SELECT -> "select ${action.ref}"
            BatchAction.Type.PRESS_KEY -> "press ${action.key}"
            BatchAction.Type.WAIT -> "wait ${action.waitMode}"
            BatchAction.Type.SCROLL -> "scroll ${action.deltaY}"
        }
        return "[${index + 1}] $label → ${if (state.success) "✅" else "❌"} ${state.toText()}"
    }
}
