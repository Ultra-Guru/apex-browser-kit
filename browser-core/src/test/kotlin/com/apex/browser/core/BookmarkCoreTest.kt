package com.apex.browser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BookmarkCore] / [BookmarkCodec] 的意图级测试：
 * 1. 去重键走 [UrlCleaner.normalizeKey]（utm 变体归一）；
 * 2. MRU 排序（lastVisitedAt 降序主导）；
 * 3. 重复 add = 更新而非报错；标题空值不覆盖旧名；
 * 4. 配额淘汰最旧；编解码 round-trip + 畸形输入零抛错。
 */
class BookmarkCoreTest {

    private fun coreOf(vararg pairs: Pair<String, String>): BookmarkCore =
        BookmarkCore(
            pairs.map { (url, title) ->
                Bookmark(
                    id = url, // 测试里以 url 当 id（等价注入）
                    url = url,
                    title = title,
                    host = UrlCleaner.hostOf(url),
                    createdAt = 1L,
                )
            }
        )

    /* ---------------- add / 去重 ---------------- */

    @Test
    fun `add rejects blank and schemeless urls`() {
        val core = BookmarkCore()
        assertEquals(BookmarkCore.AddResult.REJECTED, core.add("", "x"))
        assertEquals(BookmarkCore.AddResult.REJECTED, core.add("example.com/plain", "x"))
        assertEquals(BookmarkCore.AddResult.ADDED, core.add("https://example.com/a", "A"))
    }

    @Test
    fun `same page with tracking params merges into one bookmark`() {
        val core = BookmarkCore()
        core.add("https://example.com/post?id=9&utm_source=share", "分享来的")
        val result = core.add("https://example.com/post?id=9", "原始")
        assertEquals(BookmarkCore.AddResult.UPDATED, result)
        assertEquals(1, core.count())
        // 标题非空才覆盖：第二条带标题 → 更新为「原始」
        assertEquals("原始", core.list().first().title)
    }

    @Test
    fun `blank incoming title keeps existing name`() {
        val core = BookmarkCore()
        core.add("https://example.com/a", "我的名字")
        core.add("https://example.com/a", "")
        assertEquals("我的名字", core.list().first().title)
    }

    @Test
    fun `fallback title uses host when blank`() {
        val core = BookmarkCore()
        core.add("https://news.example.com/deep/path", "")
        assertEquals("news.example.com", core.list().first().title)
    }

    @Test
    fun `isBookmarked by normalized key`() {
        val core = BookmarkCore()
        core.add("https://example.com/a?utm_campaign=x", "A")
        assertTrue(core.isBookmarked("https://www.example.com/a"))
        assertFalse(core.isBookmarked("https://example.com/b"))
    }

    /* ---------------- 排序 / 搜索 / 文件夹 ---------------- */

    @Test
    fun `mru ordering with unvisited falling back to creation`() {
        val core = BookmarkCore()
        core.add("https://a.com/1", "a1")
        core.add("https://a.com/2", "a2")
        core.add("https://a.com/3", "a3")
        val id2 = core.list().first { it.title == "a2" }.id
        // touch 的时间戳必须严格晚于 add（同毫秒时 MRU 排序退化为插入序——
        // 真实语义即如此，测试里用 sleep 保证可判定）
        Thread.sleep(2)
        core.touch(id2)
        // 被打开过的 a2 沉到最前
        assertEquals("a2", core.list().first().title)
        // recent 截断
        assertEquals(2, core.recent(2).size)
    }

    @Test
    fun `search covers title host url folder`() {
        val core = BookmarkCore()
        core.add("https://news.example.com/politics/1", "选举结果", folder = "新闻")
        core.add("https://shop.example.com/item", "蓝牙耳机")
        assertEquals(1, core.search("选举").size)
        assertEquals(1, core.search("NEWS.example").size)     // host 命中
        assertEquals(1, core.search("politics").size)         // url 命中
        assertEquals(1, core.search("新闻").size)              // folder 命中
        assertEquals(2, core.search("example").size)
        assertTrue(core.search("  ").isNotEmpty())            // 空白 query = 全量
    }

    @Test
    fun `folders aggregate root first then alphabetical`() {
        val core = BookmarkCore()
        core.add("https://a.com/1", "a1")
        core.add("https://a.com/2", "a2", folder = "Zeta")
        core.add("https://a.com/3", "a3", folder = "Alpha")
        val names = core.folders().map { it.name }
        assertEquals(listOf(BookmarkFolderNames.ROOT, "Alpha", "Zeta"), names)
        assertEquals(1, core.folders().first { it.name == BookmarkFolderNames.ROOT }.count)
    }

    @Test
    fun `list filtered by folder`() {
        val core = BookmarkCore()
        core.add("https://a.com/1", "a1", folder = "工作")
        core.add("https://a.com/2", "a2")
        assertEquals(listOf("a1"), core.list("工作").map { it.title })
        assertEquals(2, core.list(null).size)
    }

    /* ---------------- 变更操作 ---------------- */

    @Test
    fun `rename rejects blank and moveTo resets to root`() {
        val core = BookmarkCore()
        core.add("https://a.com/1", "a1")
        val id = core.list().first().id
        assertFalse(core.rename(id, "   "))
        assertTrue(core.rename(id, "新名"))
        assertTrue(core.moveTo(id, "工作"))
        assertEquals("工作", core.list().first().folder)
        assertTrue(core.moveTo(id, "")) // 空串 = 移回根
        assertTrue(core.list().first().isInRoot)
        assertFalse(core.rename("no-such-id", "x"))
    }

    @Test
    fun `remove and clearAll`() {
        val core = BookmarkCore()
        core.add("https://a.com/1", "a1")
        core.add("https://b.com/2", "b2")
        val id = core.list().first().id
        assertTrue(core.remove(id))
        assertFalse(core.remove(id))
        assertEquals(1, core.count())
        assertEquals(1, core.clearAll())
        assertEquals(0, core.count())
    }

    /* ---------------- 配额 ---------------- */

    @Test
    fun `quota evicts oldest least visited`() {
        val core = BookmarkCore()
        repeat(BookmarkCore.MAX_BOOKMARKS) { i ->
            core.add("https://q.com/$i", "b$i")
        }
        assertEquals(BookmarkCore.MAX_BOOKMARKS, core.count())
        core.add("https://q.com/new", "new") // 触发淘汰
        assertEquals(BookmarkCore.MAX_BOOKMARKS, core.count())
        // 最旧（b0）被淘汰，new 存在
        assertNull(core.list().firstOrNull { it.title == "b0" })
        assertNotNull(core.list().firstOrNull { it.title == "new" })
    }

    /* ---------------- 编解码 ---------------- */

    @Test
    fun `codec round trip and additive default fields`() {
        val core = BookmarkCore()
        core.add("https://a.com/1", "a1", folder = "工作")
        core.add("https://b.com/2", "b2")
        val encoded = BookmarkCodec.encode(core.list())
        val decoded = BookmarkCodec.decode(encoded)
        assertEquals(core.list(), decoded)
        // 默认字段（folder/host 等）不进 JSON——encodeDefaults=false
        assertFalse(encoded.contains("\"folder\":\"\""))
    }

    @Test
    fun `codec defensive on malformed input`() {
        assertTrue(BookmarkCodec.decode(null).isEmpty())
        assertTrue(BookmarkCodec.decode("").isEmpty())
        assertTrue(BookmarkCodec.decode("not-json{").isEmpty())
        assertTrue(BookmarkCodec.decode("{\"unknown\":1}").isEmpty())
    }

    @Test
    fun `load dedupes same key keeping newer visit`() {
        val raw = BookmarkCodec.encode(
            listOf(
                Bookmark(id = "1", url = "https://a.com/x?utm_source=s", title = "旧", lastVisitedAt = 10),
                Bookmark(id = "2", url = "https://a.com/x", title = "新", lastVisitedAt = 20),
            )
        )
        val core = BookmarkCore(BookmarkCodec.decode(raw))
        assertEquals(1, core.count())
        assertEquals("新", core.list().first().title)
    }
}
