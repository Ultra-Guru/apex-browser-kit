package com.apex.browser.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/*
 * DownloadLedger —— 下载台账模型 + 编解码 + 纯合并逻辑（v1.7.0）。
 *
 * 解决的问题：系统 [android.app.DownloadManager] 是「面向用户」的——
 * App 自己入队的下载在**进程重启后拿不回查询句柄**（ID 无处可查），
 * 于是「列出本会话全部下载 / 恢复 / 取消 / 重试」这些 Playwright/
 * 桌面浏览器的基线能力在移动端普遍缺失。台账 = 把 (dmId, fileName,
 * url, mime, 入队时间) 持久化到 SharedPreferences，重启后凭 ID 续查。
 *
 * 分层：
 * - [DownloadLedgerEntry]：纯模型（@Serializable additive 尾参纪律）；
 * - [DownloadLedgerCodec]：JSON 编解码（持久化唯一边界，畸形输入
 *   返回空表——下载记录损坏的正确降级是丢台账不是起不来）；
 * - [DownloadLedger]：纯内存合并逻辑（入账 / 去重 / 裁剪 / 与
 *   「最近一次下载」这类瞬时观察值合并），JVM 可单测；
 * - DownloadManager 的 Cursor 查询与 SharedPreferences IO 住在
 *   `:browser-engine` 的 DownloadCenter——本文件不 import 任何 Android。
 *
 * 口径决策：台账只记「本库发起的下载」（chrome 人用下载 + Agent
 * 驾驶期下载），**不**吞并整个系统下载列表——后者属于系统 UI 的
 * 职责边界，混进来反而制造「删了别家下载」的事故面。
 */
@Serializable
data class DownloadLedgerEntry(
    /** 系统 DownloadManager 的下载 ID（跨进程重启仍可查询的句柄）。 */
    val dmId: Long,
    val fileName: String,
    val url: String,
    val mimeType: String? = null,
    val enqueuedAt: Long = 0L,
)

/**
 * 下载台账（纯逻辑）。
 *
 * 线程契约：与 [BookmarkCore] 相同——调用方保证串行访问；内部无锁。
 */
class DownloadLedger(initial: List<DownloadLedgerEntry> = emptyList()) {

    private val entries = LinkedHashMap<Long, DownloadLedgerEntry>(initial.size * 2)

    init {
        // 载入即按 enqueuedAt 升序排（新在尾；list() 输出前再整体倒序）
        initial.sortedBy { it.enqueuedAt }.forEach { entries[it.dmId] = it }
    }

    /**
     * 入账。同 dmId 的重复回报 = 幂等（**保留原始入账时间**，字段全等时
     * 零变化返回 false——两个下载源对同一次入队先后回报不产生写放大）；
     * 同 dmId 但字段不同（重定向后 URL 修正等）→ 字段刷新返回 true。
     */
    fun register(dmId: Long, fileName: String, url: String, mimeType: String?): Boolean {
        if (dmId <= 0L || fileName.isBlank()) return false
        val name = fileName.trim()
        val mime = mimeType?.takeIf { it.isNotBlank() }
        val existing = entries[dmId]
        if (existing != null) {
            if (existing.fileName == name && existing.url == url && existing.mimeType == mime) {
                return false
            }
            entries[dmId] = existing.copy(fileName = name, url = url, mimeType = mime)
            return true
        }
        entries[dmId] = DownloadLedgerEntry(
            dmId = dmId,
            fileName = name,
            url = url,
            mimeType = mime,
            enqueuedAt = System.currentTimeMillis(),
        )
        return true
    }

    /** 移除一条（用户在面板上删记录 / 重试换新 ID 后清旧 ID）。 */
    fun remove(dmId: Long): Boolean = entries.remove(dmId) != null

    /** 全部 ID（DownloadManager 批量查询的 setFilterById 入参）。 */
    fun ids(): LongArray = entries.keys.toLongArray()

    /** 全部条目（新在前）。 */
    fun list(): List<DownloadLedgerEntry> = entries.values.toList().asReversed()

    fun find(dmId: Long): DownloadLedgerEntry? = entries[dmId]

    fun count(): Int = entries.size

    /**
     * 与瞬时观察值合并（引擎 `lastDownload` 的「无台账孤儿」吸收）。
     *
     * Agent 驾驶期（chrome 未接线）的下载只写引擎 `lastDownload`
     *（@Volatile 瞬时值）。台账在每次刷新时把观察值吸收进来——即使
     * 进程没重启，历史也会随查询逐步累积。已存在同 URL+fileName 的
     * 近期条目时不重复入账（同 URL 快速重试的常见形态）。
     *
     * @return true 表示吸收了新条目（触发持久化）
     */
    fun absorbObserved(fileName: String?, url: String?, dmId: Long, mimeType: String? = null): Boolean {
        if (fileName.isNullOrBlank() || url.isNullOrBlank() || dmId <= 0L) return false
        if (entries.containsKey(dmId)) return false
        // 同 URL 且 60s 内已有入账 → 同一下载的二次观察，不重复
        val now = System.currentTimeMillis()
        val recentDup = entries.values.any {
            it.url == url && it.fileName == fileName && now - it.enqueuedAt < DUPLICATE_WINDOW_MS
        }
        if (recentDup) {
            // dmId 不同的同 URL 入队（重试产生新 ID）：更新既有条目的 ID 而不是双记
            val dup = entries.values.lastOrNull { it.url == url && it.fileName == fileName }
            if (dup != null && dup.dmId != dmId) {
                entries.remove(dup.dmId)
                entries[dmId] = dup.copy(dmId = dmId, enqueuedAt = now)
                return true
            }
            return false
        }
        return register(dmId, fileName, url, mimeType)
    }

    /**
     * 配额裁剪（新在前保留 [max] 条，淘汰最旧入队）。
     *
     * @return 被淘汰的 dmId 清单（DownloadCenter 无需再查询它们）
     */
    fun prune(max: Int = MAX_ENTRIES): List<Long> {
        if (entries.size <= max) return emptyList()
        val evictCount = entries.size - max
        // LinkedHashMap 顺序 = 入账升序（旧在前）→ 头部淘汰
        val victims = entries.keys.toList().take(evictCount)
        victims.forEach { entries.remove(it) }
        return victims
    }

    companion object {
        /**
         * 配额：500 条 × ~150B ≈ 75KB SharedPreferences 安全体积。
         * 与 [BookmarkCore.MAX_BOOKMARKS] 同量级考量。
         */
        const val MAX_ENTRIES: Int = 500

        /** 同 URL 去重窗口：60s 内视为同一下载的重复观察。 */
        private const val DUPLICATE_WINDOW_MS: Long = 60_000L
    }
}

/**
 * 台账 JSON 编解码（持久化唯一边界）。
 *
 * 契约与 [BookmarkCodec] 一致：编码紧凑数组 JSON；解码防御性返回空表。
 */
object DownloadLedgerCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val serializer = ListSerializer(DownloadLedgerEntry.serializer())

    fun encode(entries: List<DownloadLedgerEntry>): String = json.encodeToString(serializer, entries)

    fun decode(raw: String?): List<DownloadLedgerEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }
}
