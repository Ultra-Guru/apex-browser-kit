package com.apex.browser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BatchActionValidator] 的意图测试（v1.8.0）：批量动作提交前的字段完备性。
 *
 * 校验失败的信息直接作为工具返回文本面向模型——每条错误提示必须「可指导
 * 下一步修正」（指出第几个动作、缺什么字段），而不是笼统的 invalid。
 */
class BatchActionTest {

    private fun click(ref: String = "r_abc") = BatchAction(type = BatchAction.Type.CLICK, ref = ref)
    private fun input(ref: String = "r_in", text: String = "hello") =
        BatchAction(type = BatchAction.Type.INPUT, ref = ref, text = text)

    // ── 通过路径 ──────────────────────────────────────────────────

    @Test
    fun `合法序列通过校验`() {
        val actions = listOf(
            input(),
            click(),
            BatchAction(type = BatchAction.Type.PRESS_KEY, key = "enter"),
            BatchAction(type = BatchAction.Type.WAIT, waitMode = "url", waitValue = "result"),
        )
        assertNull(BatchActionValidator.check(actions))
    }

    @Test
    fun `SCROLL 无必填字段`() {
        assertNull(BatchActionValidator.check(listOf(BatchAction(type = BatchAction.Type.SCROLL, deltaY = -400))))
    }

    // ── 序列级约束 ────────────────────────────────────────────────

    @Test
    fun `空序列被拒绝`() {
        assertEquals(
            "Error: 批量动作序列为空（actions 至少 1 项）",
            BatchActionValidator.check(emptyList()),
        )
    }

    @Test
    fun `超过上限被拒绝并报出实际上限`() {
        val tooMany = (1..BatchAction.MAX_ACTIONS + 1).map { click() }
        val msg = BatchActionValidator.check(tooMany)
        assertTrue(msg != null && msg.contains("${BatchAction.MAX_ACTIONS}"))
    }

    // ── 字段完备性：错误提示定位到第几个动作 ─────────────────────

    @Test
    fun `CLICK 缺 ref 的提示带序号`() {
        val msg = BatchActionValidator.check(listOf(input(), click(ref = "")))
        assertEquals("Error: [2/2] CLICK 缺少 ref", msg)
    }

    @Test
    fun `INPUT 空文本且非追加被拒绝`() {
        val msg = BatchActionValidator.check(listOf(input(text = "")))
        assertEquals("Error: [1/1] INPUT 缺少 text（追加空串无意义）", msg)
    }

    @Test
    fun `INPUT 追加模式允许空文本`() {
        val appendEmpty = BatchAction(type = BatchAction.Type.INPUT, ref = "r_in", text = "", append = true)
        assertNull(BatchActionValidator.check(listOf(appendEmpty)))
    }

    @Test
    fun `SELECT 缺 value 被拒绝`() {
        val msg = BatchActionValidator.check(
            listOf(BatchAction(type = BatchAction.Type.SELECT, ref = "r_sel", value = ""))
        )
        assertTrue(msg != null && msg.contains("SELECT 缺少 value"))
    }

    @Test
    fun `WAIT 非法模式被拒绝并列出七模式`() {
        val msg = BatchActionValidator.check(
            listOf(BatchAction(type = BatchAction.Type.WAIT, waitMode = "sleep", waitValue = "5"))
        )
        assertTrue(msg != null && msg.contains("network_idle") && msg.contains("selector_count"))
    }

    @Test
    fun `WAIT 缺 value 被拒绝`() {
        val msg = BatchActionValidator.check(
            listOf(BatchAction(type = BatchAction.Type.WAIT, waitMode = "text", waitValue = ""))
        )
        assertTrue(msg != null && msg.contains("WAIT 缺少 value"))
    }

    @Test
    fun `PRESS_KEY 缺 key 被拒绝`() {
        val msg = BatchActionValidator.check(
            listOf(BatchAction(type = BatchAction.Type.PRESS_KEY, key = " "))
        )
        assertTrue(msg != null && msg.contains("PRESS_KEY 缺少 key"))
    }
}
