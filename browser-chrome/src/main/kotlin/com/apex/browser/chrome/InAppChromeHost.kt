package com.apex.browser.chrome

import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import com.apex.browser.chrome.bridge.ApexChromeWiring
import com.apex.browser.engine.BrowserEngine

/**
 * App 内嵌浏览器宿主 —— 把引擎的活动 WebView 连同完整 [BrowserChrome] 嵌进
 * 宿主 **App 自己窗口**的控制器（v1.5.0 新增）。
 *
 * 动机（宿主侧「内置浏览器到底有没有」的终极回答）：本库的可视层此前只有
 * 系统悬浮窗（TYPE_APPLICATION_OVERLAY，需 SYSTEM_ALERT_WINDOW 特殊权限且
 * 无权限时静默降级）—— 权限未授予的用户在宿主 App 里永远看不见浏览器。
 * [InAppChromeHost] 让宿主在 Activity 组合树里直接得到一个真浏览器：
 *
 * ```
 * // 宿主 Compose 页面（零悬浮窗权限）：
 * val host = InAppChromeHostFactory.get(engine, wiring)
 * Box(Modifier.fillMaxSize()) {
 *     // WebView 容器：topPadding 跟随 chrome 头部实际高度
 *     AndroidView(factory = { FrameLayout(it).also { host.attach(it) } }, ...)
 *     BrowserChrome(
 *         gateway = wiring.gateway,
 *         onChromeHeightChanged = { ... },   // 驱动容器 topPadding
 *         pageSlot = {},                     // WebView 由容器承载，避免重组销毁
 *     )
 * }
 * // 页面离开组合 / Activity ON_PAUSE：host.detach() / host.pause()
 * ```
 *
 * 职责（刻意收窄，UI 组合留在宿主）：
 * 1. **WebView 换挂**：订阅引擎 [BrowserEngine.ActiveTabListener]（多播，不与
 *    BrowserOverlay 的单回调 var 抢占）—— 标签切换/新建/崩溃重建自动换页；
 * 2. **承载权仲裁**：attach/resume → [InAppHostingArbiter.claim]（悬浮窗让位），
 *    detach/pause → release（悬浮窗按状态机恢复）；
 * 3. **chrome 数据源对齐**：attach/resync 让 [ApexChromeWiring] 链式 client
 *    （弹窗/权限/下载/快照）挂上当前活动 WebView —— in-app chrome 与悬浮窗
 *    chrome 共享同一套数据流与命令通路。
 *
 * 生命周期四动作（全部主线程）：
 * - [attach]：页面进入组合（容器可用）—— claim + resync + 换挂；
 * - [detach]：页面离开组合 —— WebView 归还引擎无头驱动 + release；
 * - [pause]：Activity ON_PAUSE（用户离开 App）—— 让出承载权，悬浮窗可接管
 *   （如 Agent 驾驶中实时卡片弹出到其他应用之上）；组合关系保留；
 * - [resume]：Activity ON_RESUME —— 重新 claim + 换挂。
 *
 * 与悬浮窗的互斥由 [InAppHostingArbiter] 保证：in-app 承载期间
 * LiveViewOverlay 的自愈换挂会被守卫拦下，不会把 WebView 从本容器抢走。
 */
class InAppChromeHost private constructor(
    private val engine: BrowserEngine,
    private val wiring: ApexChromeWiring,
) : BrowserEngine.ActiveTabListener {

    companion object {

        @Volatile
        private var instance: InAppChromeHost? = null

        /**
         * 进程级单例（与 [BrowserOverlayFactory]/[LiveViewOverlayFactory] 同款纪律：
         * 库零 DI 依赖，宿主 Hilt Module 里 provide 即可）。
         * 创建后立即订阅引擎活动标签通知 —— 引擎回调按注册序派发，请先创建
         * BrowserOverlay 再创建本宿主（与 LiveViewOverlayFactory 同序约定）。
         */
        fun get(engine: BrowserEngine, wiring: ApexChromeWiring): InAppChromeHost =
            instance ?: synchronized(this) {
                instance ?: InAppChromeHost(engine, wiring).also { instance = it }
            }

        /** 仅测试用：重置单例。 */
        @Synchronized
        fun reset() {
            instance = null
        }
    }

    /** 宿主容器（attach 设置 / detach 清空）。 */
    private var container: FrameLayout? = null

    /** 当前挂进容器的 WebView（换页时换引用）。 */
    private var attachedWebView: WebView? = null

    /** attach 且未 pause（承载权持有中）。 */
    private var active = false

    init {
        engine.addActiveTabListener(this)
    }

    /** 页面进入组合：容器接管活动 WebView + 承载权 claim + chrome 数据源对齐。 */
    fun attach(container: FrameLayout) {
        this.container = container
        active = true
        InAppHostingArbiter.claim()
        wiring.resync()
        rebind(engine.activeWebView())
    }

    /** 页面离开组合：WebView 归还引擎无头驱动 + 承载权 release。 */
    fun detach() {
        active = false
        container = null
        detachWebView()
        InAppHostingArbiter.release()
    }

    /** Activity ON_PAUSE：让出承载权（悬浮窗可视层按状态机接管）；组合关系保留。 */
    fun pause() {
        if (!active) return
        active = false
        detachWebView()
        InAppHostingArbiter.release()
    }

    /** Activity ON_RESUME：恢复承载（容器仍在 = 页面未离开组合）。 */
    fun resume() {
        if (active || container == null) return
        active = true
        InAppHostingArbiter.claim()
        wiring.resync()
        rebind(engine.activeWebView())
    }

    // ───────── ActiveTabListener：标签换页（引擎主线程通知） ─────────

    override fun onActiveTabChanged(webView: WebView?) {
        if (!active) return
        rebind(webView)
    }

    // ───────── WebView 换挂（与两个悬浮窗同款再父级纪律） ─────────

    private fun rebind(view: WebView?) {
        val host = container ?: return
        if (view == null) {
            // 无活动页（会话收尾/全部关闭）：清挂即可，容器空置由宿主 UI 表达
            attachedWebView = null
            return
        }
        if (view.parent === host) {
            attachedWebView = view
            return
        }
        (view.parent as? ViewGroup)?.removeView(view)
        host.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        attachedWebView = view
    }

    private fun detachWebView() {
        val wv = attachedWebView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        attachedWebView = null
    }
}

/**
 * App 内嵌浏览器宿主进程级单例工厂（库零 DI 依赖，宿主在自己的 Hilt Module
 * 里 provide 即可，见 [BrowserOverlayFactory] 同款接法）。
 *
 * 注册顺序提示：请先创建 [BrowserOverlay] 再创建本宿主（引擎活动标签回调按
 * 注册序派发；顺序异常时仲裁守卫仍保证互斥正确，只影响换挂时序微差）。
 */
object InAppChromeHostFactory {

    @Volatile
    private var instance: InAppChromeHost? = null

    fun get(engine: BrowserEngine, wiring: ApexChromeWiring): InAppChromeHost =
        instance ?: synchronized(this) {
            instance ?: InAppChromeHost.get(engine, wiring).also { instance = it }
        }

    /** 仅测试用：重置单例。 */
    @Synchronized
    fun reset() {
        instance = null
    }
}
