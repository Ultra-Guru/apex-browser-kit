package com.apex.browser.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/*
 * Bookmarks —— 书签数据模型 + 纯逻辑核心 + JSON 编解码（v1.7.0）。
 *
 * 分层（与 [PageClassifier]「策略下沉 core、IO 留上层」同款纪律）：
 * - [Bookmark] / [BookmarkFolder]：纯数据模型（@Serializable）；
 * - [BookmarkCore]：全部业务规则（去重 / 排序 / 搜索 / 配额 / 最近访问），
 *   无 IO、无 Android，JVM 可单测；
 * - [BookmarkCodec]：JSON 编解码（kotlinx.serialization，`:browser-core`
 *   已依赖）——持久化的**唯一**序列化边界，字段演进必须走 additive 尾参；
 * - 引擎侧 `BookmarkStore`（SharedPreferences 实现）住在 `:browser-engine`，
 *   只做 IO 与线程边界，不做规则。
 *
 * 设计取舍：
 * 1. **去重键用 [UrlCleaner.normalizeKey]**：同一文章的「分享链接（带
 *    utm）」与「原始链接」归一书签——否则书签栏很快被同一页面以三四种
 *    参数形态霸占（移动端分享场景的高频痛点）；
 * 2. **最近访问置顶排序（MRU）**：移动端书签的支配性用法是「回到刚才
 *    那个页面」而非档案管理，`folder` 只是二级组织维度；
 * 3. **配额 [MAX_BOOKMARKS] = 500**：SharedPreferences 单文件反序列化在
 *    千条量级开始卡主线程（全量读写），500 条 × ~200B ≈ 100KB 是安全区。
 */
@Serializable
data class Bookmark(
    /** 稳定 id（UUID）。地址栏「星标」等无标题场景由调用方补默认标题。 */
    val id: String,
    /** 原始 URL（保留用户看到的形态——含 query；清理仅用于去重键）。 */
    val url: String,
    val title: String,
    /** 所属文件夹；空串 = 根目录（[BookmarkFolder.ROOT]）。 */
    val folder: String = "",
    val createdAt: Long = 0L,
    /** 最近一次通过书签打开的时间戳（MRU 排序键；0 = 从未打开）。 */
    val lastVisitedAt: Long = 0L,
    /** 展示用主机名（写入时快照，避免列表渲染时重复计算）。 */
    val host: String = "",
) {
    val isInRoot: Boolean get() = folder.isBlank()
}

/** 文件夹视图模型（由 [BookmarkCore.folders] 聚合产出，不是独立持久化实体）。 */
data class BookmarkFolder(
    val name: String,
    val count: Int,
)

/** 兜底标题：URL 拿不到任何可读标题时的最终回退。 */
fun Bookmark.fallbackTitle(): String =
    title.takeIf { it.isNotBlank() } ?: host.takeIf { it.isNotBlank() } ?: url

/**
 * 书签业务核心（纯逻辑，全部同步函数，无 IO）。
 *
 * 线程契约：调用方（`BookmarkStore`）保证串行访问（主线程或自有锁）；
 * 本类不做内部同步——规则函数都很轻（<500 条遍历），同步开销只会
 * 掩盖调用方的线程模型。
 */
class BookmarkCore(initial: List<Bookmark> = emptyList()) {

    private val byId = LinkedHashMap<String, Bookmark>(initial.size * 2)
    /** 去重键 → id。normalizeKey 冲突（同 URL 不同参数形态）指向同一条。 */
    private val keyIndex = HashMap<String, String>(initial.size * 2)

    init {
        initial.forEach { ingest(it, touchExisting = false) }
    }

    /* ---------------- 读 ---------------- */

    /** 全量快照（MRU：lastVisitedAt 降序，未访问按 createdAt 降序）。 */
    fun list(folder: String? = null): List<Bookmark> =
        byId.values.asSequence()
            .filter { folder == null || it.folder == folder.trim() }
            .sortedWith(
                compareByDescending<Bookmark> { it.lastVisitedAt }
                    .thenByDescending { it.createdAt }
            )
            .toList()

    /** 全文搜索：标题 > host > URL，包含匹配（大小写不敏感）。 */
    fun search(query: String, limit: Int = 50): List<Bookmark> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return list().take(limit)
        return byId.values.asSequence()
            .filter {
                it.title.lowercase().contains(q) ||
                    it.host.lowercase().contains(q) ||
                    it.url.lowercase().contains(q) ||
                    it.folder.lowercase().contains(q)
            }
            .sortedWith(compareByDescending<Bookmark> { it.lastVisitedAt }.thenByDescending { it.createdAt })
            .take(limit.coerceIn(1, MAX_BOOKMARKS))
            .toList()
    }

    /** 文件夹聚合（根目录固定排首位，其余按名称字典序）。 */
    fun folders(): List<BookmarkFolder> {
        val grouped = byId.values.groupBy { it.folder.trim() }
        val rootCount = grouped[""]?.size ?: 0
        val named = grouped.entries
            .filter { it.key.isNotBlank() }
            .map { BookmarkFolder(it.key, it.value.size) }
            .sortedBy { it.name }
        return buildList(capacity = named.size + 1) {
            if (rootCount > 0) add(BookmarkFolder(BookmarkFolderNames.ROOT, rootCount))
            addAll(named)
        }
    }

    /** 最近书签（「回到刚才」快捷入口）。 */
    fun recent(limit: Int): List<Bookmark> =
        list().take(limit.coerceIn(1, MAX_BOOKMARKS))

    fun find(id: String): Bookmark? = byId[id]

    /** 是否已收藏（按归一化键）。地址栏星标的瞬时判定。 */
    fun isBookmarked(url: String): Boolean = keyIndex.containsKey(UrlCleaner.normalizeKey(url))

    fun count(): Int = byId.size

    /* ---------------- 写 ---------------- */

    /**
     * 添加书签。已存在同键书签时**刷新**（更新标题/时间戳并提升 MRU），
     * 返回 [AddResult.UPDATED]——「重复点星标」的常见用户意图是更新而非报错。
     *
     * @return [AddResult]：ADDED（新增，可能因配额淘汰最旧条目）/
     *    UPDATED（同键更新）/ REJECTED（参数非法）
     */
    fun add(url: String, title: String, folder: String = ""): AddResult {
        if (url.isBlank() || !url.contains("://") && !url.startsWith("about:")) return AddResult.REJECTED
        val key = UrlCleaner.normalizeKey(url)
        val existingId = keyIndex[key]
        val now = System.currentTimeMillis()
        if (existingId != null) {
            val old = byId[existingId] ?: return AddResult.REJECTED
            // 标题非空才覆盖：分享链接常拿不到标题，别用它抹掉用户改过的名字
            val nextTitle = title.takeIf { it.isNotBlank() } ?: old.title
            byId[existingId] = old.copy(
                title = nextTitle,
                folder = folder.takeIf { it.isNotBlank() } ?: old.folder,
                lastVisitedAt = now,
            )
            return AddResult.UPDATED
        }
        val bm = Bookmark(
            id = newId(),
            url = url,
            title = title.takeIf { it.isNotBlank() } ?: UrlCleaner.hostOf(url),
            folder = folder.trim(),
            createdAt = now,
            lastVisitedAt = now,
            host = UrlCleaner.hostOf(url),
        )
        byId[bm.id] = bm
        keyIndex[key] = bm.id
        evictIfNeeded()
        return AddResult.ADDED
    }

    /** 删除书签；不存在返回 false。 */
    fun remove(id: String): Boolean {
        val bm = byId.remove(id) ?: return false
        keyIndex.remove(UrlCleaner.normalizeKey(bm.url))
        return true
    }

    /** 重命名（title）。空标题拒绝（书签必须有可读名）。 */
    fun rename(id: String, newTitle: String): Boolean {
        if (newTitle.isBlank()) return false
        val bm = byId[id] ?: return false
        byId[id] = bm.copy(title = newTitle.trim())
        return true
    }

    /** 移动到文件夹（空串 = 移回根目录）。目标不存在时静默建夹（文件夹非实体）。 */
    fun moveTo(id: String, folder: String): Boolean {
        val bm = byId[id] ?: return false
        byId[id] = bm.copy(folder = folder.trim())
        return true
    }

    /** 通过书签打开时打卡（MRU 提升）。 */
    fun touch(id: String): Boolean {
        val bm = byId[id] ?: return false
        byId[id] = bm.copy(lastVisitedAt = System.currentTimeMillis())
        return true
    }

    /** 清空（「清除浏览数据」的书签面）。返回清除条数。 */
    fun clearAll(): Int {
        val n = byId.size
        byId.clear()
        keyIndex.clear()
        return n
    }

    /* ---------------- 内部 ---------------- */

    private fun ingest(bm: Bookmark, touchExisting: Boolean) {
        val key = UrlCleaner.normalizeKey(bm.url)
        val existingId = keyIndex[key]
        if (existingId != null) {
            // 持久化载入遇到重复键：保留 lastVisitedAt 较新的一条（时间即真源）
            val old = byId[existingId]!!
            val winner = if (touchExisting || bm.lastVisitedAt >= old.lastVisitedAt) bm else old
            byId[existingId] = winner.copy(id = existingId)
            return
        }
        byId[bm.id] = bm
        keyIndex[key] = bm.id
    }

    /** 配额淘汰：MRU 尾部（最旧且最少访问）逐条出局。 */
    private fun evictIfNeeded() {
        while (byId.size > MAX_BOOKMARKS) {
            val victim = byId.values.minWithOrNull(
                compareBy<Bookmark> { it.lastVisitedAt }.thenBy { it.createdAt }
            ) ?: break
            remove(victim.id)
        }
    }

    private fun newId(): String =
        java.util.UUID.randomUUID().toString()

    /** [add] 的结果语义（供 UI 文案区分「已加入」vs「已更新」）。 */
    enum class AddResult { ADDED, UPDATED, REJECTED }

    companion object {
        /** 配额：SharedPreferences 单文件全量读写的安全区上界。 */
        const val MAX_BOOKMARKS: Int = 500
    }
}

/** 文件夹显示名约定（空串在数据层表示根，展示层换成可读名）。 */
object BookmarkFolderNames {
    const val ROOT: String = "根目录"
}

/**
 * 书签 JSON 编解码（持久化唯一边界）。
 *
 * 契约（与 [DomParser] 的快照契约同哲学）：
 * - 编码始终产出 `[Bookmark]` 数组的紧凑 JSON；
 * - 解码**防御性**：任何畸形输入返回空列表而不是抛错——书签文件损坏
 *   的正确降级是「丢书签」而非「浏览器起不来」；
 * - 字段演进 additive：新增字段必须带默认值，旧文件解码零改动。
 */
object BookmarkCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val serializer = ListSerializer(Bookmark.serializer())

    fun encode(bookmarks: List<Bookmark>): String = json.encodeToString(serializer, bookmarks)

    fun decode(raw: String?): List<Bookmark> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }
}
