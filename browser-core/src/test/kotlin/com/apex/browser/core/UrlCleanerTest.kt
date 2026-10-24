package com.apex.browser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UrlCleaner] 的意图级测试：
 * 1. **幂等**——clean(clean(x)) == clean(x)（书签去重依赖）；
 * 2. **只删不改**——保留参数顺序 / fragment / 裸布尔键；
 * 3. **零失败面**——畸形输入（未转义中文 / 空格 / 多个 ? / 空 query）原样返回；
 * 4. 白名单口径——语义两可参数（ref / source / id）不删。
 */
class UrlCleanerTest {

    /* ---------------- 清理主体 ---------------- */

    @Test
    fun `strips google analytics family`() {
        val url = "https://example.com/page?utm_source=x&utm_medium=y&utm_campaign=z&keep=1"
        assertEquals("https://example.com/page?keep=1", UrlCleaner.clean(url))
    }

    @Test
    fun `strips ad click ids`() {
        val url = "https://example.com/l?gclid=abc&fbclid=def&msclkid=ghi&yclid=jkl&ttclid=mno"
        assertEquals("https://example.com/l", UrlCleaner.clean(url))
    }

    @Test
    fun `keeps semantic ambiguous params`() {
        // ref / source / id 是大量站点的功能性参数——误删即断链
        val url = "https://example.com/item?ref=nav&id=42&source=feed"
        assertEquals(url, UrlCleaner.clean(url))
    }

    @Test
    fun `keeps bare boolean keys`() {
        val url = "https://example.com/search?q=hello&flag"
        assertEquals(url, UrlCleaner.clean(url))
    }

    @Test
    fun `drops empty valued params`() {
        val url = "https://example.com/p?empty=&q=1"
        assertEquals("https://example.com/p?q=1", UrlCleaner.clean(url))
    }

    @Test
    fun `removes trailing question when query fully cleaned`() {
        assertEquals("https://example.com/p", UrlCleaner.clean("https://example.com/p?utm_source=x"))
        // 已无悬挂问号
        assertFalse(UrlCleaner.clean("https://example.com/p?fbclid=1").contains('?'))
    }

    @Test
    fun `preserves fragment and order`() {
        val url = "https://example.com/doc?b=2&a=1&utm_term=k#section-3"
        assertEquals("https://example.com/doc?b=2&a=1#section-3", UrlCleaner.clean(url))
    }

    @Test
    fun `value containing equals sign kept intact`() {
        // a=b=c 的值是 b=c（首个 = 为边界）——跟踪参数 a 若保留则值必须原样
        val url = "https://example.com/v?redirect=https%3A%2F%2Fother.com%3Fx%3D1"
        assertEquals(url, UrlCleaner.clean(url))
    }

    @Test
    fun `no query returns input unchanged`() {
        assertEquals("https://example.com/p", UrlCleaner.clean("https://example.com/p"))
        assertEquals("", UrlCleaner.clean(""))
    }

    @Test
    fun `malformed urls never throw`() {
        // 未转义中文 + 空格：java.net.URI 会抛，UrlCleaner 必须原样返回
        val weird = "https://example.com/搜 索?q=关 键 字&utm_source=app"
        val cleaned = UrlCleaner.clean(weird)
        assertEquals("https://example.com/搜 索?q=关 键 字", cleaned)
        // 双 ?（畸形）：首个为界，后续并入 query 段过滤
        UrlCleaner.clean("https://example.com/a??b=1&utm_id=2")
    }

    @Test
    fun `idempotent across representative corpus`() {
        val corpus = listOf(
            "https://example.com/p?utm_source=a&b=1",
            "https://example.com/p?b=1",
            "https://example.com/p",
            "https://example.com/p?gclid=x#frag",
            "about:blank",
        )
        corpus.forEach { u ->
            val once = UrlCleaner.clean(u)
            assertEquals("idempotence violated for $u", once, UrlCleaner.clean(once))
        }
    }

    @Test
    fun `prefix families matched`() {
        // utm_xxx 扩展 / hsa_ / pk_ / spm / xsec_ / share_ 前缀族
        val url = "https://example.com/p?utm_custom=1&hsa_cam=2&pk_kwd=3&spm=a2.0&xsec_token=9&share_token=7"
        assertEquals("https://example.com/p", UrlCleaner.clean(url))
    }

    /* ---------------- isTrackingParam ---------------- */

    @Test
    fun `tracking detection case insensitive and trimmed`() {
        assertTrue(UrlCleaner.isTrackingParam("UTM_Source"))
        assertTrue(UrlCleaner.isTrackingParam(" fbclid "))
        assertFalse(UrlCleaner.isTrackingParam("q"))
        assertFalse(UrlCleaner.isTrackingParam("ref"))
        assertFalse(UrlCleaner.isTrackingParam(""))
    }

    /* ---------------- cleanOrNull / removedParams ---------------- */

    @Test
    fun `cleanOrNull null when nothing removed`() {
        assertNull(UrlCleaner.cleanOrNull("https://example.com/p?a=1"))
        assertEquals(
            "https://example.com/p",
            UrlCleaner.cleanOrNull("https://example.com/p?utm_source=x"),
        )
    }

    @Test
    fun `removedParams ordered deduped capped`() {
        val removed = UrlCleaner.removedParams("https://e.com/p?utm_source=1&utm_medium=2&utm_source=3&b=2")
        assertEquals(listOf("utm_source", "utm_medium"), removed)
        assertTrue(UrlCleaner.removedParams("https://e.com/p?a=1").isEmpty())
        // 上限保护：34 个跟踪参数只报前 32 个
        val many = "https://e.com/p?" + (1..34).joinToString("&") { "utm_v$it=x" }
        assertEquals(32, UrlCleaner.removedParams(many).size)
    }

    /* ---------------- hostOf / normalizeKey ---------------- */

    @Test
    fun `hostOf strips scheme www port userinfo`() {
        assertEquals("example.com", UrlCleaner.hostOf("https://www.example.com/a?b=1"))
        assertEquals("example.com", UrlCleaner.hostOf("http://example.com:8080/x"))
        assertEquals("example.com", UrlCleaner.hostOf("https://user:pw@example.com/"))
        assertEquals("", UrlCleaner.hostOf("not a url"))
    }

    @Test
    fun `normalizeKey folds www default port trailing slash and fragment`() {
        // www 前缀 / 默认端口 443 / 末尾斜杠 → 同键
        assertEquals(
            UrlCleaner.normalizeKey("https://www.example.com/a/"),
            UrlCleaner.normalizeKey("https://example.com:443/a"),
        )
        // 同一文章的不同章节（fragment）→ 同键（书签是页面级概念）
        assertEquals(
            UrlCleaner.normalizeKey("https://example.com/a#section-2"),
            UrlCleaner.normalizeKey("https://example.com/a"),
        )
        // http:80 与 https 是不同来源（不同 TLS 边界），不能折叠
        assertFalse(
            UrlCleaner.normalizeKey("https://example.com/a") ==
                UrlCleaner.normalizeKey("http://example.com/a")
        )
    }

    @Test
    fun `normalizeKey dedupes tracking variants`() {
        // 同一文章的分享链接（带 utm）与原始链接 → 同键
        assertEquals(
            UrlCleaner.normalizeKey("https://example.com/post?id=9&utm_source=share"),
            UrlCleaner.normalizeKey("https://www.example.com/post?id=9"),
        )
    }
}
