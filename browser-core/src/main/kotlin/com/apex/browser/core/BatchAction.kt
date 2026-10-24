package com.apex.browser.core

/**
 * 批量动作描述（v1.8.0）—— 一次工具调用串行执行多个浏览器动作。
 *
 * 动机：Agent 每个动作（click/input/select…）都是一次完整的「模型往返 →
 * 工具执行 → 结果回传」循环，秒级成本；表单填写（输标题 → 选分类 →
 * 勾选项 → 点提交）这类**动作序列已确定**的场景，逐个往返是纯浪费。
 * 引擎 `BrowserEngine.batchExecute` 接收一组 [BatchAction]，
 * 引擎内串行执行、逐个返回动作后验证状态，把 N 次模型往返压缩为 1 次。
 *
 * 纪律：
 * - 序列上限 [MAX_ACTIONS]（8）——批量不是脚本引擎，超长序列应拆步骤观察结果；
 * - 任一动作失败即**停止后续动作**（失败上下文对下一步决策有意义，盲跑到底
 *   会把页面带到未知状态）；
 * - WAIT 动作可插在序列中间，等异步内容到达后再继续（等效 browser_wait_for）。
 *
 * 纯 Kotlin（无 Android 依赖）：校验逻辑（[BatchActionValidator]）可在 JVM 单测覆盖。
 */
data class BatchAction(
    val type: Type,
    /** 目标元素稳定 ref（CLICK/INPUT/SELECT 必填） */
    val ref: String = "",
    /** INPUT 的文本内容 */
    val text: String = "",
    /** INPUT 追加模式（默认整段替换） */
    val append: Boolean = false,
    /** INPUT 后回车提交 */
    val pressEnter: Boolean = false,
    /** SELECT 的选项值 */
    val value: String = "",
    /** SELECT 按可见文本匹配（默认按 value） */
    val byText: Boolean = false,
    /** PRESS_KEY 的键名 */
    val key: String = "",
    /** WAIT 的等待模式（waitForCondition 七模式之一） */
    val waitMode: String = "",
    /** WAIT 的条件值 */
    val waitValue: String = "",
    /** WAIT 的超时毫秒 */
    val waitTimeoutMs: Long = 5_000,
    /** SCROLL 的滚动像素（正向下负向上） */
    val deltaY: Int = 0,
) {
    enum class Type { CLICK, INPUT, SELECT, PRESS_KEY, WAIT, SCROLL }

    companion object {
        /** 单次批量序列的动作数上限（防把批量当脚本引擎用） */
        const val MAX_ACTIONS: Int = 8
    }
}

/**
 * 批量动作的执行结果定义在引擎侧（`BrowserEngine.BatchOutcome`，持有
 * 引擎的 PostActionState）；本文件只承载纯数据与校验。
 */
/**
 * 批量动作提交前校验（纯 JVM 可测）：字段完备性与语义合法性。
 * 返回 null 表示通过；非 null 为「面向模型」的错误提示（可直接作为工具
 * 返回文本），首个错误即返回（不聚合——模型按提示逐个修正更稳）。
 */
object BatchActionValidator {

    /** 与引擎 waitForCondition 的模式白名单保持一致（七模式）。 */
    private val WAIT_MODES = setOf(
        "selector", "text", "url", "element_gone", "text_gone", "network_idle", "selector_count"
    )

    fun check(actions: List<BatchAction>): String? {
        if (actions.isEmpty()) return "Error: 批量动作序列为空（actions 至少 1 项）"
        if (actions.size > BatchAction.MAX_ACTIONS) {
            return "Error: 批量动作数 ${actions.size} 超过上限 ${BatchAction.MAX_ACTIONS}（应拆步骤观察结果）"
        }
        actions.forEachIndexed { i, a ->
            val n = "[${i + 1}/${actions.size}]"
            when (a.type) {
                BatchAction.Type.CLICK ->
                    if (a.ref.isBlank()) return "Error: $n CLICK 缺少 ref"
                BatchAction.Type.INPUT ->
                    if (a.ref.isBlank()) return "Error: $n INPUT 缺少 ref"
                    else if (a.text.isBlank() && !a.append) return "Error: $n INPUT 缺少 text（追加空串无意义）"
                BatchAction.Type.SELECT ->
                    if (a.ref.isBlank()) return "Error: $n SELECT 缺少 ref"
                    else if (a.value.isBlank()) return "Error: $n SELECT 缺少 value"
                BatchAction.Type.PRESS_KEY ->
                    if (a.key.isBlank()) return "Error: $n PRESS_KEY 缺少 key"
                BatchAction.Type.WAIT -> {
                    if (a.waitMode !in WAIT_MODES) {
                        return "Error: $n WAIT 的 mode '${a.waitMode}' 非法（可用：${WAIT_MODES.joinToString("|")}）"
                    }
                    if (a.waitValue.isBlank()) return "Error: $n WAIT 缺少 value"
                }
                BatchAction.Type.SCROLL -> Unit // deltaY 可正可负可缺省
            }
        }
        return null
    }
}
