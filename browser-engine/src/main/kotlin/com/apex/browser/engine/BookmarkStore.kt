package com.apex.browser.engine

import android.content.Context
import com.apex.browser.core.Bookmark
import com.apex.browser.core.BookmarkCodec
import com.apex.browser.core.BookmarkCore
import com.apex.browser.core.BookmarkFolder
import com.apex.browser.core.UrlCleaner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 书签持久化仓库（v1.7.0，`SharedPreferences` 实现）。
 *
 * 分层：规则全在 [BookmarkCore]（`:browser-core` 纯 JVM 可单测），本类只做
 * IO 与线程边界——这正是 [com.apex.browser.chrome.bridge.PermissionPersistence]
 *（SharedPreferencesGrantStore）的既有模式。
 *
 * 消费方：
 * - chrome 层「书签面板 / 地址栏星标」（经 BrowserEngineGateway 的
 *   additive 成员，见 bridge 侧接线）；
 * - 宿主 Agent 工具 `BrowserBookmarks`（list / add / remove / search）。
 *
 * 线程契约：全部公开方法 `@Synchronized`——chrome 在主线程调、Agent 工具
 * 在 IO 线程调，双源并发是常态；SharedPreferences 的 apply() 本身异步落盘，
 * 锁只保护 [BookmarkCore] 与快照流的一致性。
 *
 * 去重语义：同一页面（[UrlCleaner.normalizeKey] 归一后相等）只保留一条
 * 书签——分享链接携带的 utm/fbclid 变体不再把书签栏刷成同一篇文章的
 * 三四种形态。add 同键 = 更新（MRU 提升），详见 [BookmarkCore.add]。
 */
class BookmarkStore private constructor(
    appContext: Context,
) {

    private val prefs = appContext.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val core = BookmarkCore(BookmarkCodec.decode(prefs.getString(KEY_BOOKMARKS, null)))

    private val _bookmarks = MutableStateFlow(core.list())
    /** 书签只读快照流（新在前 MRU；UI 与工具共用的唯一事实源）。 */
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    /* ---------------- 读 ---------------- */

    @Synchronized
    fun list(folder: String? = null): List<Bookmark> = core.list(folder)

    @Synchronized
    fun search(query: String, limit: Int = 50): List<Bookmark> = core.search(query, limit)

    @Synchronized
    fun recent(limit: Int): List<Bookmark> = core.recent(limit)

    @Synchronized
    fun folders(): List<BookmarkFolder> = core.folders()

    @Synchronized
    fun find(id: String): Bookmark? = core.find(id)

    @Synchronized
    fun isBookmarked(url: String): Boolean = core.isBookmarked(url)

    @Synchronized
    fun count(): Int = core.count()

    /* ---------------- 写（每次变更即持久化 + 快照流发布） ---------------- */

    @Synchronized
    fun add(url: String, title: String, folder: String = ""): BookmarkCore.AddResult {
        val result = core.add(url, title, folder)
        if (result != BookmarkCore.AddResult.REJECTED) publishAndPersist()
        return result
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val removed = core.remove(id)
        if (removed) publishAndPersist()
        return removed
    }

    @Synchronized
    fun rename(id: String, newTitle: String): Boolean {
        val ok = core.rename(id, newTitle)
        if (ok) publishAndPersist()
        return ok
    }

    @Synchronized
    fun moveTo(id: String, folder: String): Boolean {
        val ok = core.moveTo(id, folder)
        if (ok) publishAndPersist()
        return ok
    }

    /** 通过书签打开时打卡（MRU 提升；导航由调用方执行）。 */
    @Synchronized
    fun touch(id: String): Boolean {
        val ok = core.touch(id)
        if (ok) publishAndPersist()
        return ok
    }

    /** 清空书签（「清除浏览数据」的书签面）。返回清除条数。 */
    @Synchronized
    fun clearAll(): Int {
        val n = core.clearAll()
        if (n > 0) publishAndPersist()
        return n
    }

    /* ---------------- 内部 ---------------- */

    private fun publishAndPersist() {
        val snapshot = core.list()
        _bookmarks.value = snapshot
        prefs.edit().putString(KEY_BOOKMARKS, BookmarkCodec.encode(snapshot)).apply()
    }

    companion object {
        private const val PREFS_NAME = "apex_browser_bookmarks"
        private const val KEY_BOOKMARKS = "bookmarks_v1"

        /**
         * 进程级单例工厂（库化去 Hilt 的统一纪律，与
         * [com.apex.browser.engine.di.BrowserEngineFactory] 同款）。
         * chrome 接线（ApexChromeWiring）与宿主 DI（BrowserKitModule）
         * 必须拿到**同一个**实例，否则书星/面板与 Agent 工具各改各的。
         */
        @Volatile
        private var instance: BookmarkStore? = null

        fun get(context: Context): BookmarkStore =
            instance ?: synchronized(this) {
                instance ?: BookmarkStore(context).also { instance = it }
            }

        /** 仅测试用：重置单例。 */
        @Synchronized
        fun reset() {
            instance = null
        }
    }
}

/** 进程级单例工厂的独立宿主（保持与 BrowserEngineFactory 相同的文件组织习惯）。 */
object BookmarkStoreFactory {

    fun get(context: Context): BookmarkStore = BookmarkStore.get(context)

    @Synchronized
    fun reset() = BookmarkStore.reset()
}
