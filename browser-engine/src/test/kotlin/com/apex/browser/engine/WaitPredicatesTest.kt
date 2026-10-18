package com.apex.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 `waitForCondition(selector_count)` 谓词解析与判定的「意图」（v1.3.0）：
 *
 * - 解析成功/失败边界：算子形态支持任意空白；纯数字形态（语义 >=）要求与选择器间
 *   有空白——否则 `.item2` 这类以数字结尾的**合法选择器**会被误拆成「选择器 .item +
 *   计数 2」，等待条件从此永远错位。这是 value 语法与 CSS 语法交叠处的核心歧义，
 *   解析失败的直接后果是静默超时（等 10s 等不到），所以解析必须保守。
 * - 判定语义：`>=` / `<=` / `==` / `>` / `<` 五算子与纯数字缺省 `>=`。
 *
 * 纯字符串逻辑（不触 WebView），JVM 单测穷举。
 */
class WaitPredicatesTest {

    // ── 解析：算子形态 ─────────────────────────────────────────────

    @Test
    fun `算子形态解析出选择器与计数谓词`() {
        val p = parseCountPredicate("li.item >= 10")
        assertNotNull(p)
        assertEquals("li.item", p!!.selector)
        assertEquals(">=", p.op)
        assertEquals(10, p.n)
    }

    @Test
    fun `算子与数字间的空白可有可无`() {
        // `<选择器>>=10`（无空白）与 `<选择器> >= 10`（有空白）等价
        val noSpace = parseCountPredicate("div.search-result>=25")
        val spaced = parseCountPredicate("div.search-result >= 25")
        assertNotNull(noSpace)
        assertNotNull(spaced)
        assertEquals(noSpace!!.selector, spaced!!.selector)
        assertEquals(noSpace.op, spaced.op)
        assertEquals(noSpace.n, spaced.n)
    }

    @Test
    fun `五个算子都被识别`() {
        assertEquals(">=", parseCountPredicate("a >= 1")!!.op)
        assertEquals("<=", parseCountPredicate("a <= 1")!!.op)
        assertEquals("==", parseCountPredicate("a == 1")!!.op)
        assertEquals(">", parseCountPredicate("a > 1")!!.op)
        assertEquals("<", parseCountPredicate("a < 1")!!.op)
    }

    @Test
    fun `子代组合选择器含空格也能解析`() {
        // div > a 形态：选择器自身的空格不能被误认为 value 分隔
        val p = parseCountPredicate("div.card > a >= 3")
        assertNotNull(p)
        assertEquals("div.card > a", p!!.selector)
        assertEquals(">=", p.op)
        assertEquals(3, p.n)
    }

    // ── 解析：纯数字形态（缺省 >=） ────────────────────────────────

    @Test
    fun `纯数字形态缺省语义为大于等于`() {
        // 「等到至少 N 个」是等待场景的主导用法
        val p = parseCountPredicate("li.product 10")
        assertNotNull(p)
        assertEquals("li.product", p!!.selector)
        assertEquals(">=", p.op)
        assertEquals(10, p.n)
    }

    @Test
    fun `以数字结尾的选择器不被纯数字形态误拆`() {
        // .item2 是合法 CSS 类选择器：若无空白要求会被拆成 selector=.item + n=2，
        // 等待条件从此静默错位。解析失败（null）才是正确行为——调用方据此给出
        // 可诊断错误而非静默超时。
        assertNull(parseCountPredicate(".item2"))
        // 同理：#anchor1 / a:nth-child(3) 这类数字结尾形态拒绝解析
        assertNull(parseCountPredicate("#anchor1"))
        assertNull(parseCountPredicate("a:nth-child(3)"))
    }

    // ── 解析：拒绝畸形输入 ─────────────────────────────────────────

    @Test
    fun `畸形输入一律返回 null 而非抛错`() {
        for (bad in listOf("", "   ", ">=10", "abc", "abc >= ", "abc >= x", ">= x")) {
            assertNull("畸形输入 '$bad' 应解析失败", parseCountPredicate(bad))
        }
    }

    @Test
    fun `首尾空白被容错剥离`() {
        val p = parseCountPredicate("  .result  5  ")
        assertNotNull(p)
        assertEquals(".result", p!!.selector)
        assertEquals(5, p.n)
    }

    // ── 判定语义 ───────────────────────────────────────────────────

    @Test
    fun `matchesCount 五算子判定正确`() {
        assertTrue(matchesCount(10, parseCountPredicate("a >= 10")!!))
        assertFalse(matchesCount(9, parseCountPredicate("a >= 10")!!))
        assertTrue(matchesCount(5, parseCountPredicate("a <= 5")!!))
        assertFalse(matchesCount(6, parseCountPredicate("a <= 5")!!))
        assertTrue(matchesCount(3, parseCountPredicate("a == 3")!!))
        assertFalse(matchesCount(4, parseCountPredicate("a == 3")!!))
        assertTrue(matchesCount(4, parseCountPredicate("a > 3")!!))
        assertFalse(matchesCount(3, parseCountPredicate("a > 3")!!))
        assertTrue(matchesCount(2, parseCountPredicate("a < 3")!!))
        assertFalse(matchesCount(3, parseCountPredicate("a < 3")!!))
    }

    @Test
    fun `纯数字缺省形态与显式大于等于判定一致`() {
        val implicit = parseCountPredicate("li 5")!!
        val explicit = parseCountPredicate("li >= 5")!!
        for (count in intArrayOf(4, 5, 6)) {
            assertEquals(
                "count=$count",
                matchesCount(count, implicit),
                matchesCount(count, explicit),
            )
        }
    }
}
