package com.apex.browser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReaderParser] 的意图级测试：
 * 1. readerDocumentJs 回传 JSON 的契约形态（blocks 按文档顺序 / kind wire 值）；
 * 2. 目录（chapters）由 HEADING 块聚合且锚点正确；
 * 3. 防御性：畸形输入返回 null 绝不抛错；
 * 4. [ReaderParser.plainText] 的结构化拍平（标题 # 前缀 / 引用 > 前缀 / 代码围栏）。
 */
class ReaderModeTest {

    /** 与 readerDocumentJs 回传形态一致的 fixture（内层 JSON，已剥 evaluateJavascript 外壳）。 */
    private val articleJson = """
        {
          "url": "https://example.com/post/1",
          "title": "论浏览器自动化",
          "siteName": "Example Weekly",
          "byline": "张三",
          "publishedTime": "2026-10-01",
          "blocks": [
            {"kind":"heading","text":"引言","level":2},
            {"kind":"paragraph","text":"浏览器自动化正在从脚本走向智能体。"},
            {"kind":"heading","text":"物理触摸","level":3},
            {"kind":"quote","text":"isTrusted 校验挡不住 dispatchTouchEvent。"},
            {"kind":"code","text":"function f(){\n  return 1;\n}"},
            {"kind":"image","text":"","alt":"架构图","src":"https://cdn.example.com/img/arch.png?v=2"},
            {"kind":"list_item","text":"语义哈希 ref","level":0},
            {"kind":"list_item","text":"显式握手","level":0},
            {"kind":"paragraph","text":"结论：细节决定成败。"}
          ]
        }
    """.trimIndent()

    @Test
    fun `parse maps wire kinds and keeps document order`() {
        val doc = ReaderParser.parse(articleJson)
        assertNotNull(doc)
        doc!!
        assertEquals("论浏览器自动化", doc.title)
        assertEquals("张三", doc.byline)
        assertEquals("Example Weekly", doc.siteName)
        assertEquals("2026-10-01", doc.publishedTime)
        assertEquals(9, doc.blocks.size)
        assertEquals(ReaderBlockKind.HEADING, doc.blocks[0].kind)
        assertEquals(2, doc.blocks[0].level)
        assertEquals(ReaderBlockKind.IMAGE, doc.blocks[5].kind)
        assertEquals("架构图", doc.blocks[5].alt)
        // src 摘要：去 query，留 host · 文件名
        assertEquals("cdn.example.com · arch.png", doc.blocks[5].srcSummary)
        // 文档顺序 = blocks 顺序（不是按类型分组）
        assertEquals(ReaderBlockKind.PARAGRAPH, doc.blocks[8].kind)
    }

    @Test
    fun `chapters aggregate headings with block anchors`() {
        val doc = ReaderParser.parse(articleJson)!!
        assertEquals(2, doc.chapters.size)
        assertEquals("引言", doc.chapters[0].title)
        assertEquals(0, doc.chapters[0].blockIndex)
        assertEquals(3, doc.chapters[1].level)
        assertEquals(2, doc.chapters[1].blockIndex)
    }

    @Test
    fun `unknown wire kind falls back to paragraph`() {
        val json = """{"url":"https://a.com","title":"T","blocks":[{"kind":"mystery","text":"x"}]}"""
        val doc = ReaderParser.parse(json)!!
        assertEquals(ReaderBlockKind.PARAGRAPH, doc.blocks[0].kind)
    }

    @Test
    fun `empty text non-image blocks dropped`() {
        val json = """{"url":"https://a.com","title":"T","blocks":[
            {"kind":"paragraph","text":"   "},
            {"kind":"image","text":"","alt":"无字图","src":"https://a.com/i.png"}
        ]}"""
        val doc = ReaderParser.parse(json)!!
        assertEquals(1, doc.blocks.size)
        assertEquals(ReaderBlockKind.IMAGE, doc.blocks[0].kind)
    }

    @Test
    fun `defensive on malformed inputs`() {
        assertNull(ReaderParser.parse(null))
        assertNull(ReaderParser.parse(""))
        assertNull(ReaderParser.parse("null"))
        assertNull(ReaderParser.parse("{broken"))
        assertNull(ReaderParser.parse("""{"url":"https://a.com","title":"T","blocks":[]}"""))
        // blocks 缺失
        assertNull(ReaderParser.parse("""{"url":"https://a.com","title":"T"}"""))
    }

    @Test
    fun `missing title falls back to host`() {
        val json = """{"url":"https://news.example.com/x","title":"","blocks":[{"kind":"paragraph","text":"p"}]}"""
        val doc = ReaderParser.parse(json)!!
        assertEquals("news.example.com", doc.title)
    }

    @Test
    fun `meaningfulness gate`() {
        val thin = """{"url":"https://a.com","title":"T","blocks":[{"kind":"paragraph","text":"only one"}]}"""
        assertFalse(ReaderParser.parse(thin)!!.isMeaningful)
        assertTrue(ReaderParser.parse(articleJson)!!.isMeaningful)
    }

    @Test
    fun `reading minutes positive and monotone`() {
        val doc = ReaderParser.parse(articleJson)!!
        assertTrue(doc.readingMinutes >= 1)
        val longer = doc.copy(
            blocks = doc.blocks + ReaderBlock(ReaderBlockKind.PARAGRAPH, text = "字".repeat(700))
        )
        assertTrue(longer.readingMinutes >= doc.readingMinutes)
    }

    @Test
    fun `plainText renders structure markers`() {
        val text = ReaderParser.plainText(ReaderParser.parse(articleJson)!!)
        assertTrue(text.startsWith("## 目录"))          // 目录先行
        assertTrue(text.contains("# 论浏览器自动化"))    // 文档主标题
        assertTrue(text.contains("## 引言"))            // h2 → ##
        assertTrue(text.contains("### 物理触摸"))       // h3 → ###
        assertTrue(text.contains("> isTrusted"))        // 引用前缀
        assertTrue(text.contains("```\nfunction f(){"))  // 代码围栏
        assertTrue(text.contains("[图片：架构图]"))       // 图片占位
        assertTrue(text.contains("- 语义哈希 ref"))      // 列表前缀
        assertTrue(text.contains("作者：张三"))
        assertTrue(text.contains("来源：Example Weekly"))
    }

    @Test
    fun `plainText without outline when chapters empty`() {
        val json = """{"url":"https://a.com","title":"无标题文","blocks":[{"kind":"paragraph","text":"正文"},{"kind":"paragraph","text":"续"}]}"""
        val text = ReaderParser.plainText(ReaderParser.parse(json)!!, includeOutline = false)
        assertFalse(text.startsWith("## 目录"))
        assertTrue(text.contains("# 无标题文"))
    }

    @Test
    fun `blocks capped at 400`() {
        val blocks = (1..500).joinToString(",") {
            """{"kind":"paragraph","text":"p$it"}"""
        }
        val json = """{"url":"https://a.com","title":"T","blocks":[$blocks]}"""
        assertEquals(400, ReaderParser.parse(json)!!.blocks.size)
    }
}
