package com.apex.browser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7.0 新增脚本的契约测试（与 [BrowserScriptTest] 同一套「生成物断言」哲学）：
 * 1. 注入边界——外部字符串（ref）只经 [JsLiteral.string]；
 * 2. 无 Promise（evaluateJavascript 不等待 Promise，v1.0.0 的教训）；
 * 3. 生成 JS 的关键语义片段（几何字段 / 钳制 / 幂等守卫 / 目录块）。
 */
class BrowserScriptV17Test {

    /* ---------------- PAGE_GEOMETRY_JS ---------------- */

    @Test
    fun `geometry script reads both documentElement and body`() {
        val js = BrowserScript.PAGE_GEOMETRY_JS
        assertTrue(js.contains("scrollHeight"))
        assertTrue(js.contains("viewportH"))
        // 双源取大：body 撑开的旧式页面不被截短
        assertTrue(js.contains("Math.max"))
        // 无 Promise、同步返回 JSON
        assertFalse(js.contains("new Promise"))
        assertTrue(js.contains("JSON.stringify"))
    }

    /* ---------------- scrollToYJs ---------------- */

    @Test
    fun `scrollToY clamps to legal range`() {
        assertTrue(BrowserScript.scrollToYJs(-50).contains("scrollTo(0, 0)"))
        assertTrue(BrowserScript.scrollToYJs(0).contains("scrollTo(0, 0)"))
        val big = BrowserScript.scrollToYJs(99_999_999)
        assertTrue(big.contains("scrollTo(0, ${BrowserScript.MAX_SCROLL_PX})"))
        assertFalse(big.contains("99999999"))
        // 注入的是数值字面量（非外部字符串 → 无注入面）
        assertTrue(BrowserScript.scrollToYJs(1200).contains("scrollTo(0, 1200)"))
    }

    /* ---------------- READER_DOCUMENT_JS ---------------- */

    @Test
    fun `reader script emits structured blocks and metadata`() {
        val js = BrowserScript.READER_DOCUMENT_JS
        // 六种块类型全部在采集白名单里
        listOf("h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "img", "li").forEach {
            assertTrue("selector $it missing", js.contains(it))
        }
        // 元数据：og:site_name / author / article:published_time
        assertTrue(js.contains("og:site_name"))
        assertTrue(js.contains("article:author"))
        assertTrue(js.contains("article:published_time"))
        // 正文根选取与 article 模式同源
        assertTrue(js.contains("article"))
        assertTrue(js.contains("[role=main]"))
        // 块数与单块文本硬上限（与 ReaderParser 口径一致）
        assertTrue(js.contains("blocks.length<400"))
        assertTrue(js.contains("4000"))
        // 防御：整体 try/catch → 畸形页面返回 null 而非抛错
        assertTrue(js.contains("catch(e)"))
        // 无 Promise
        assertFalse(js.contains("new Promise"))
    }

    @Test
    fun `reader script keeps code formatting`() {
        // pre 的 keepBreaks 分支：不做 \s+ 折叠
        assertTrue(BrowserScript.READER_DOCUMENT_JS.contains("keepBreaks"))
    }

    /* ---------------- SELECTION_TEXT_JS ---------------- */

    @Test
    fun `selection script defensive and synchronous`() {
        val js = BrowserScript.SELECTION_TEXT_JS
        assertTrue(js.contains("getSelection"))
        assertTrue(js.contains("catch(e)"))
        assertFalse(js.contains("new Promise"))
    }

    /* ---------------- elementTextJs ---------------- */

    @Test
    fun `elementText routes ref through JsLiteral`() {
        val hostile = "r'); alert(document.cookie); ('"
        val js = BrowserScript.elementTextJs(hostile)
        // ref 只以编码字面量出现（与 rectByRefJs 同款 refLookupJs 复用）
        assertTrue(js.contains(JsLiteral.string(hostile)))
        // 首个匹配未命中 → null（调用方按 ElementNotFound 语义处理）
        assertTrue(js.contains("JSON.stringify(null)"))
        // 文本截断保护
        assertTrue(js.contains("20000"))
    }

    /* ---------------- FORCE_DARK_JS / OFF ---------------- */

    @Test
    fun `force dark is idempotent and reverts cleanly`() {
        val on = BrowserScript.FORCE_DARK_JS
        val off = BrowserScript.FORCE_DARK_OFF_JS
        // 幂等守卫（重复注入零效果）
        assertTrue(on.contains("__apexForceDark"))
        assertTrue(on.contains("if (window.__apexForceDark) return true"))
        // 媒体二次反转（图片不变负片）
        assertTrue(on.contains("img,video,picture,canvas,svg,iframe"))
        // style 节点带固定 id（OFF 可定位摘除）
        assertTrue(on.contains("__apex-force-dark-style"))
        assertTrue(off.contains("__apex-force-dark-style"))
        assertTrue(off.contains("removeChild"))
        // 均无 Promise
        assertFalse(on.contains("new Promise"))
        assertFalse(off.contains("new Promise"))
    }
}
