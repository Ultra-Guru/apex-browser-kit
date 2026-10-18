package com.apex.browser.chrome.bridge

import com.apex.browser.chrome.ChromeBookmark
import com.apex.browser.chrome.ChromeDisplaySettings
import com.apex.browser.core.Bookmark
import com.apex.browser.core.BookmarkCore
import com.apex.browser.core.ReaderDocument
import com.apex.browser.core.ReaderParser
import com.apex.browser.engine.BrowserEngine
import com.apex.browser.engine.BookmarkStore
import com.apex.browser.engine.EngineDisplayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/*
 * ChromeSessionControls —— v1.7.0 会话增强能力的接线契约 + 引擎侧实现。
 *
 * 分层（与 TabOperations / EngineCommands 同款「接口在 bridge、实现可插」）：
 * - 接口：chrome UI 需要的**能力面**（书签 / 显示设置 / 阅读模式）；
 * - [EngineSessionControls]：本项目 [BrowserEngine] 的默认实现，全部
 *   委托引擎侧组件（[BookmarkStore] / [EngineDisplayController] /
 *   READER_DOCUMENT_JS 采集 + ReaderParser）——零新依赖；
 * - 接线：[ApexChromeWiring] 构造时创建并注入 NeonChromeWiring →
 *   BrowserEngineGatewayAdapter，chrome UI 经 Gateway 消费。
 */

/** v1.7.0 会话增强能力束（书签 / 显示控制 / 阅读模式）。 */
interface ChromeSessionControls {

    /** 书签快照（新在前 MRU）。 */
    val bookmarks: StateFlow<List<ChromeBookmark>>

    /** 收藏（同键已存在 = 更新）。返回 false = 参数非法。 */
    fun addBookmark(url: String, title: String): Boolean

    /** 删除书签；不存在返回 false。 */
    fun removeBookmark(id: String): Boolean

    /** 通过书签打开时的打卡（MRU 提升）。 */
    fun touchBookmark(id: String)

    /** 显示设置快照。 */
    val displaySettings: StateFlow<ChromeDisplaySettings>

    fun setTextZoom(percent: Int)

    fun setForceDark(enabled: Boolean)

    fun clearBrowsingData(clearCookies: Boolean, clearCaches: Boolean, clearHistories: Boolean)

    /** 页面加载完成后重放显示偏好（夜间模式的导航重注入口）。 */
    fun reapplyForLoadedPage()

    /** 渲染当前页阅读模式文档；页面不适合时 null。 */
    suspend fun renderReaderPage(): ReaderDocument?
}

/**
 * 引擎侧默认实现：全部委托引擎组件，构造零副作用（组件自身是单例工厂产物）。
 *
 * @param scope 主线程域（与 ApexChromeWiring 的 scope 同源——flow 收集
 *   与 JS 注入都在主线程排队，与引擎「WebView 主线程纪律」一致）
 */
class EngineSessionControls(
    private val engine: BrowserEngine,
    private val bookmarksStore: BookmarkStore,
    private val display: EngineDisplayController,
    scope: CoroutineScope,
) : ChromeSessionControls {

    override val bookmarks: StateFlow<List<ChromeBookmark>> =
        bookmarksStore.bookmarks
            .map { list -> list.map { it.toChrome() } }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override val displaySettings: StateFlow<ChromeDisplaySettings> =
        display.settings
            .map { ChromeDisplaySettings(textZoomPercent = it.textZoomPercent, forceDark = it.forceDark) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), ChromeDisplaySettings())

    override fun addBookmark(url: String, title: String): Boolean =
        bookmarksStore.add(url, title) != BookmarkCore.AddResult.REJECTED

    override fun removeBookmark(id: String): Boolean = bookmarksStore.remove(id)

    override fun touchBookmark(id: String) {
        bookmarksStore.touch(id)
    }

    override fun setTextZoom(percent: Int) = display.setTextZoom(percent)

    override fun setForceDark(enabled: Boolean) = display.setForceDark(enabled)

    override fun clearBrowsingData(clearCookies: Boolean, clearCaches: Boolean, clearHistories: Boolean) =
        display.clearBrowsingData(clearCookies, clearCaches, clearHistories)

    override fun reapplyForLoadedPage() = display.reapplyForCurrentPage()

    override suspend fun renderReaderPage(): ReaderDocument? {
        // executeJavaScript 回传是「JSON 字符串字面量」编码（stringify 收尾的
        // 脚本再被 evaluateJavascript 编一层）——与引擎 unwrapWebViewJson 同
        // 口径剥壳后再交给 ReaderParser。剥壳用 org.json.JSONTokener（Android
        // 平台内置——chrome 模块不引 kotlinx.serialization，与 NeonChromeWiring
        // 的 org.json 用法同款纪律）。
        val raw = runCatching {
            engine.executeJavaScript(com.apex.browser.core.BrowserScript.READER_DOCUMENT_JS)
        }.getOrNull() ?: return null
        val inner = runCatching {
            when (val v = org.json.JSONTokener(raw).nextValue()) {
                is String -> v
                else -> raw
            }
        }.getOrDefault(raw)
        return ReaderParser.parse(inner)
    }
}

/** 引擎 Bookmark → chrome 视图模型（host 截断保 UI 不溢出）。 */
private fun Bookmark.toChrome(): ChromeBookmark = ChromeBookmark(
    id = id,
    url = url,
    title = title.take(120),
    folder = folder,
    host = host,
)
