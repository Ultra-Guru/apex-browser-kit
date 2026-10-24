package com.apex.browser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DownloadLedger] / [DownloadLedgerCodec] 的意图级测试：
 * 1. 入账幂等（同 dmId 重复回报刷新而非双记）；
 * 2. 「最近一次下载」瞬时观察值的吸收语义（去重窗口 / 重试换 ID 归并）；
 * 3. 配额裁剪淘汰最旧；编解码 round-trip + 防御性。
 */
class DownloadLedgerTest {

    @Test
    fun `register idempotent refresh`() {
        val ledger = DownloadLedger()
        assertTrue(ledger.register(1L, "a.pdf", "https://a.com/a.pdf", "application/pdf"))
        // 同 dmId 同字段再回报（chrome 路径 + 引擎路径先后回报同一入队）
        // → 幂等：保留原始入账时间，零变化（不触发持久化写放大）
        assertFalse(ledger.register(1L, "a.pdf", "https://a.com/a.pdf", "application/pdf"))
        assertEquals(1, ledger.count())
        assertEquals(1L, ledger.ids().first())
        assertEquals(listOf(1L), ledger.ids().toList())
        // 同 dmId 字段变化（重定向修正 URL）→ 刷新返回 true
        assertTrue(ledger.register(1L, "a.pdf", "https://cdn.a.com/a.pdf", "application/pdf"))
        assertEquals(1, ledger.count())
    }

    @Test
    fun `register rejects invalid entries`() {
        val ledger = DownloadLedger()
        assertFalse(ledger.register(0L, "a.pdf", "https://a.com", null))
        assertFalse(ledger.register(-1L, "a.pdf", "https://a.com", null))
        assertFalse(ledger.register(2L, "  ", "https://a.com", null))
        assertTrue(ledger.isEmpty())
    }

    private fun DownloadLedger.isEmpty(): Boolean = count() == 0

    @Test
    fun `list newest first and find`() {
        val ledger = DownloadLedger()
        ledger.register(10L, "old.bin", "https://a.com/old", null)
        Thread.sleep(2) // 保证 enqueuedAt 可区分
        ledger.register(11L, "new.bin", "https://a.com/new", null)
        assertEquals(listOf(11L, 10L), ledger.list().map { it.dmId })
        assertEquals("new.bin", ledger.find(11L)?.fileName)
        assertEquals(null, ledger.find(99L))
    }

    @Test
    fun `remove prunes single entry`() {
        val ledger = DownloadLedger()
        ledger.register(1L, "a", "https://a.com", null)
        ledger.register(2L, "b", "https://b.com", null)
        assertTrue(ledger.remove(1L))
        assertFalse(ledger.remove(1L))
        assertEquals(listOf(2L), ledger.ids().toList())
    }

    /* ---------------- 观察值吸收 ---------------- */

    @Test
    fun `absorb observed adds orphan download`() {
        val ledger = DownloadLedger()
        // 引擎驾驶期的下载只进 lastDownload（无台账）→ 刷新时吸收
        assertTrue(ledger.absorbObserved("report.csv", "https://a.com/report.csv", 77L))
        assertEquals(1, ledger.count())
        assertEquals("report.csv", ledger.find(77L)?.fileName)
    }

    @Test
    fun `absorb ignores blank and duplicate id`() {
        val ledger = DownloadLedger()
        assertFalse(ledger.absorbObserved(null, "https://a.com", 5L))
        assertFalse(ledger.absorbObserved("a.pdf", null, 5L))
        ledger.register(5L, "a.pdf", "https://a.com/a.pdf", null)
        // 同 dmId：已入账的观察值不产生变化
        assertFalse(ledger.absorbObserved("a.pdf", "https://a.com/a.pdf", 5L))
        assertEquals(1, ledger.count())
    }

    @Test
    fun `absorb merges same url within dedupe window`() {
        val ledger = DownloadLedger()
        ledger.register(5L, "a.pdf", "https://a.com/a.pdf", null)
        // 同 URL + 同名 + 60s 内 + 新 dmId = 重试产生的新 ID → 归并（换 id 不双记）
        assertTrue(ledger.absorbObserved("a.pdf", "https://a.com/a.pdf", 6L))
        assertEquals(1, ledger.count())
        assertEquals(6L, ledger.ids().first())
    }

    @Test
    fun `absorb same id within window is silent`() {
        val ledger = DownloadLedger()
        ledger.register(5L, "a.pdf", "https://a.com/a.pdf", null)
        // 同 dmId 同 URL：既已入账，观察值不产生变化
        assertFalse(ledger.absorbObserved("a.pdf", "https://a.com/a.pdf", 5L))
        assertEquals(1, ledger.count())
    }

    /* ---------------- 配额 ---------------- */

    @Test
    fun `prune evicts oldest beyond quota`() {
        val ledger = DownloadLedger()
        // dmId 从 1 起（0 会被 register 拒绝——合法性防线的一部分）
        repeat(DownloadLedger.MAX_ENTRIES) { i ->
            ledger.register((i + 1).toLong(), "f$i", "https://a.com/$i", null)
        }
        val victims = ledger.prune(max = DownloadLedger.MAX_ENTRIES - 10)
        assertEquals(10, victims.size)
        // 最早入账的 1..10 被淘汰
        assertEquals((1L..10L).toList(), victims)
        assertEquals(DownloadLedger.MAX_ENTRIES - 10, ledger.count())
        assertEquals(500L, ledger.ids().maxOrNull())
        // 未超配额时 prune 空手而归
        assertTrue(ledger.prune(max = DownloadLedger.MAX_ENTRIES).isEmpty())
    }

    /* ---------------- 编解码 ---------------- */

    @Test
    fun `codec round trip`() {
        val ledger = DownloadLedger()
        ledger.register(1L, "a.pdf", "https://a.com/a.pdf", "application/pdf")
        Thread.sleep(2)
        ledger.register(2L, "b.zip", "https://b.com/b.zip", null)
        val decoded = DownloadLedgerCodec.decode(DownloadLedgerCodec.encode(ledger.list()))
        assertEquals(ledger.list(), decoded)
        // mimeType null 不进 JSON（encodeDefaults=false）
        val encoded = DownloadLedgerCodec.encode(ledger.list())
        assertTrue(encoded.contains("\"mimeType\":\"application/pdf\""))
        assertFalse(encoded.contains("\"mimeType\":null"))
    }

    @Test
    fun `codec defensive on malformed input`() {
        assertTrue(DownloadLedgerCodec.decode(null).isEmpty())
        assertTrue(DownloadLedgerCodec.decode("][{not json").isEmpty())
    }

    @Test
    fun `load sorts by enqueue time`() {
        val raw = DownloadLedgerCodec.encode(
            listOf(
                DownloadLedgerEntry(dmId = 9L, fileName = "late", url = "u9", enqueuedAt = 300),
                DownloadLedgerEntry(dmId = 4L, fileName = "early", url = "u4", enqueuedAt = 100),
                DownloadLedgerEntry(dmId = 7L, fileName = "mid", url = "u7", enqueuedAt = 200),
            )
        )
        val ledger = DownloadLedger(DownloadLedgerCodec.decode(raw))
        // 新在前（enqueuedAt 降序）
        assertEquals(listOf(9L, 7L, 4L), ledger.list().map { it.dmId })
    }
}
