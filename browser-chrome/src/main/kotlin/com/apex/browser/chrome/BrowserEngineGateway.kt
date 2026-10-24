package com.apex.browser.chrome

import com.apex.browser.core.ReaderDocument
import kotlinx.coroutines.flow.StateFlow

/*
 * BrowserEngineGateway —— Chrome 层与自动化引擎之间的唯一边界。
 *
 * 设计动机：
 * - Chrome UI 不 import 任何 WebView/引擎类型，因此可以：
 *   a) 用 FakeEngineGateway 做 @Preview 与 UI 调试；
 *   b) 用 JVM 单测覆盖「UI 状态机」而不需要 Android 设备；
 *   c) 引擎未来重构 / 更换内核时，Chrome 层零改动。
 * - 你们的引擎已具备 newTab / switchTab / goBack / reload 等能力，
 *   只需一个薄适配器（见 bridge/BrowserEngineGatewayAdapter.kt）即可满足本接口。
 */
interface BrowserEngineGateway {

    /* ---------------- 状态源 ---------------- */

    /** 标签与导航状态的唯一事实源。 */
    val state: StateFlow<EngineSnapshot>

    /** 待处理的 JS 弹窗（首个元素即当前展示的弹窗）。 */
    val dialogRequests: StateFlow<List<JsDialogRequest>>

    /** 待处理的网页权限请求。 */
    val permissionRequests: StateFlow<List<PermissionRequest>>

    /** 下载列表（新在前）。 */
    val downloads: StateFlow<List<DownloadItem>>

    /* ---------------- 导航 ---------------- */

    fun goBack()
    fun goForward()
    fun reload()
    fun stopLoading()

    /** 直接加载 URL（应已归一化，见 UrlUtils.resolveInput）。 */
    fun loadUrl(url: String)

    /** 以搜索引擎处理非 URL 输入。 */
    fun search(query: String)

    /* ---------------- 标签 ---------------- */

    /** 新建标签，返回标签 id；url 传 null 表示打开默认页。 */
    fun newTab(url: String?): String

    fun switchTab(tabId: String)

    /** 立即关闭（Chrome 层的撤销宽限已在控制器内消化，引擎无需感知）。 */
    fun closeTab(tabId: String)

    fun closeAllTabs()

    /* ---------------- 页内查找 ---------------- */

    fun findInPage(query: String, forward: Boolean)

    fun clearFindMatches()

    /* ---------------- 站点行为 ---------------- */

    fun setDesktopMode(enabled: Boolean)

    fun openInExternalBrowser(url: String)

    /* ---------------- 输入与对话 ---------------- */

    /** 剪贴板中可「粘贴即走」的 URL；无则 null。 */
    fun clipboardUrlOrNull(): String? = null

    /**
     * 用户对 JS 弹窗的裁决。
     * @param routeToAgent 用户选择让 Agent 代替裁决（引擎侧异步给出最终答复）。
     */
    fun resolveDialog(
        requestId: String,
        choice: JsDialogChoice,
        promptValue: String? = null,
        routeToAgent: Boolean = false,
    )

    fun resolvePermission(requestId: String, decision: PermissionDecision)

    /**
     * 撤销某 origin 的全部「总是允许」记忆授权（站点权限管理界面用）。
     * 未接持久化时为空操作。
     */
    fun revokeRememberedGrants(originHost: String) {}

    /* ---------------- 下载 ---------------- */

    fun cancelDownload(id: String) {}
    fun retryDownload(id: String) {}
    fun openDownload(id: String) {}

    /* ---------------- 历史（可选，供地址栏联想） ---------------- */

    suspend fun queryHistory(query: String, limit: Int): List<HistoryEntry> = emptyList()

    /* ---------------- v1.7.0 会话增强（全部可选能力，默认未接线即隐藏） ----------------
     *
     * 设计口径与 [clipboardUrlOrNull] / [revokeRememberedGrants] 一致：能力以
     * 「可空 Flow / 布尔返回」暴露，适配器（BrowserEngineGatewayAdapter 的
     * sessionControls 参数）接上即点亮；FakeGateway / 预览场景零改动。 */

    /** 书签快照（新在前 MRU）；null = 书签能力未接线（菜单项自动隐藏）。 */
    fun bookmarksOrNull(): StateFlow<List<ChromeBookmark>>? = null

    /** 收藏页面（同键已存在 = 更新）。返回 false = 未接线或参数非法。 */
    fun addBookmark(url: String, title: String): Boolean = false

    /** 删除书签；返回 false = 不存在或未接线。 */
    fun removeBookmark(id: String): Boolean = false

    /** 通过书签打开时的打卡（MRU 提升）。 */
    fun touchBookmark(id: String) {}

    /** 显示设置快照（文字缩放 / 夜间模式）；null = 未接线（菜单项自动隐藏）。 */
    fun displaySettingsOrNull(): StateFlow<ChromeDisplaySettings>? = null

    /** 设置文字缩放（50..200，100 = 原生）。 */
    fun setTextZoom(percent: Int) {}

    /** 开关夜间模式（算法反色，导航后由 chrome 重注）。 */
    fun setForceDark(enabled: Boolean) {}

    /** 清除浏览数据（Cookie 全局 / 缓存 / WebView 原生历史）。 */
    fun clearBrowsingData(clearCookies: Boolean, clearCaches: Boolean, clearHistories: Boolean) {}

    /** 页面加载完成后的显示偏好重放入口（chrome 层 onPageFinished 钩子调）。 */
    fun reapplyDisplayForLoadedPage() {}

    /** 渲染当前页的阅读模式文档；null = 页面不适合或能力未接线。 */
    suspend fun renderReaderPage(): ReaderDocument? = null
}
