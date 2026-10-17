package com.apex.browser.chrome

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.apex.browser.chrome.bridge.ApexChromeWiring
import com.apex.browser.engine.BrowserEngine
import com.apex.browser.engine.OverlayLifecycleOwner
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 内置浏览器浮窗 —— 完整「给人用的浏览器界面」层（chrome）。
 *
 * 架构（三层解耦）：
 * - BrowserEngine：无头自动化引擎（Agent 工具层直用，零改动）；
 * - ApexChromeWiring：引擎↔chrome 接线器（链式 client 接管弹窗/权限/下载，
 *   快照自动合成，人机双通道裁决路由）；
 * - BrowserChrome：引擎无关的浏览器外观（地址胶囊即进度条 / 标签条 / 页内查找 /
 *   下载面板 / 弹窗权限浮层）——本类只负责把它挂进 overlay 窗口。
 *
 * 与引擎共享同一 WebView 实例（接管期间人类直接真实触摸，交还后 detach 回引擎）。
 * 显式握手：WAITING_HUMAN 时浮窗展开 + chrome 顶部接管横幅（「我已完成操作」），
 * Agent 自动化工具被锁；HIDDEN/AGENT_DRIVING 状态浮窗自动收起（霓虹球仍在，
 * 点球即再次进入接管）。WebView 容器的 topMargin 由 chrome 头部实际高度回调驱动，
 * 页面内容永不遮挡地址栏。
 *
 * ## v1.5.0：与 in-app 内嵌浏览器互斥
 *
 * 宿主可用 [InAppChromeHost] 把完整浏览器嵌进 App 自己的窗口（零悬浮窗权限）。
 * in-app 承载期间（[InAppHostingArbiter.isInAppHosting]）：WAITING_HUMAN 不再
 * 自动展开本浮窗（in-app chrome 就是人的 UI），已展开则收起；in-app 退场
 *（页面离开/Activity 暂停）且处于 WAITING_HUMAN 时自动重新展开 —— 用户
 * 离开 App 后接管浮窗接管体验不变。
 *
 * ## P0 修复：浮窗不再抢占键盘焦点
 *
 * 旧窗口 flags 缺 `FLAG_NOT_FOCUSABLE`：全屏 overlay 一出就抢走应用窗口焦点，
 * 下方应用的 EditText 被 IMM 判定 “窗口未聚焦”而拒绝弹键盘（经典
 * ignoring showSoftInput 报错）。现在窗口默认不可聚焦（纯触摸层），
 * 首次在浮窗内按下时才切为可聚焦（页面输入框/地址栏需要 IME），隐藏时复位。
 * 触摸分发不受可聚焦性影响（WebView 滚动/点击照常），只有键盘输入需要它。
 */
class BrowserOverlay private constructor(
    private val appContext: Context,
    private val engine: BrowserEngine,
    private val chromeWiring: ApexChromeWiring,
    private val visualHook: BrowserVisualHook? = null,
) : BrowserEngine.BrowserUiCallback {

    companion object {
        fun create(
            context: Context,
            engine: BrowserEngine,
            wiring: ApexChromeWiring,
            visualHook: BrowserVisualHook? = null,
        ): BrowserOverlay = BrowserOverlay(context.applicationContext, engine, wiring, visualHook)
    }

    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // 单例常驻 mainScope：Main.immediate + 异常记录不崩溃（与引擎/球一致的生命周期纪律）
    private val mainScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            android.util.Log.w("BrowserOverlay", "handoff failed", e)
        }
    )

    @Volatile private var rootView: FrameLayout? = null
    @Volatile private var webViewHost: FrameLayout? = null
    @Volatile private var composeView: ComposeView? = null
    // 每次展示周期新建：LifecycleRegistry 一旦 ON_DESTROY 无法重置
    @Volatile private var lifecycleOwner: OverlayLifecycleOwner? = null
    @Volatile private var windowParams: WindowManager.LayoutParams? = null
    /** 当前窗口是否已因浮窗内触摸而切为可聚焦（IME 需要窗口焦点）。 */
    @Volatile private var windowFocusable = false

    @Volatile private var attachedWebView: WebView? = null

    /** chrome 头部（地址栏+横幅+标签条）实测高度，px；驱动 WebView 容器留白。 */
    @Volatile private var chromeHeaderPx: Int = 0

    /** in-app 承载权变化：进场（收让）/退场（WAITING_HUMAN 补展开）——先于 init 声明。 */
    private val arbiterListener = InAppHostingArbiter.Listener { active ->
        mainHandler.post {
            when {
                active -> if (rootView != null) doHide()
                !active -> if (
                    engine.currentState == BrowserEngine.BrowserSessionState.WAITING_HUMAN &&
                    rootView == null
                ) doShow()
            }
        }
    }

    init {
        engine.addUiCallback(this)
        // 标签切换/新建/崩溃重建 → 换挂 WebView（wiring 已把链式 client 挂好）
        chromeWiring.onActiveWebViewChanged = { view ->
            mainHandler.post { rebindWebView(view) }
        }
        // v1.5.0：in-app 承载仲裁 —— in-app 进场收起本浮窗，退场时按状态机补展开
        InAppHostingArbiter.addListener(arbiterListener)
    }

    // ───────── BrowserUiCallback ─────────
    override fun onStateChanged(
        state: BrowserEngine.BrowserSessionState,
        url: String?,
        title: String?,
    ) {
        mainHandler.post {
            when (state) {
                BrowserEngine.BrowserSessionState.WAITING_HUMAN -> {
                    // v1.5.0：in-app 承载期间人工接管由宿主 App 内嵌 chrome 展示，
                    // 本浮窗不展开（避免双 UI 抢同一 WebView）；退场时仲裁监听补展开
                    if (!InAppHostingArbiter.isInAppHosting) show()
                    visualHook?.onWaitingHuman()
                }
                BrowserEngine.BrowserSessionState.AGENT_DRIVING -> {
                    hide()
                    visualHook?.onAgentDrivingStarted()
                }
                else -> {
                    hide()
                    visualHook?.onSessionHidden()
                }
            }
        }
    }

    // ───────── 显式展开/收起（供工具或外部调用） ─────────
    fun show() = mainHandler.post { doShow() }
    fun hide() = mainHandler.post { doHide() }

    @SuppressLint("ClickableViewAccessibility")
    private fun doShow() {
        if (rootView != null) {
            // 已显示：对齐标签结构并刷新 WebView 绑定（如 Agent 换了 activeTab）
            chromeWiring.resync()
            rebindWebView(engine.activeWebView())
            return
        }
        val params = buildLayoutParams()
        val root = TouchActivatingFrameLayout(appContext).apply {
            onFirstTouch = { makeWindowFocusable() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        // WebView 承载容器：topMargin 由 chrome 头部高度回调实时驱动（初始估计值，
        // 首帧 onGloballyPositioned 回调后即校正），页面内容永不遮挡地址栏。
        // 同款触摸激活：页面内输入框需要窗口焦点才能拉起 IME。
        val host = TouchActivatingFrameLayout(appContext).apply {
            onFirstTouch = { makeWindowFocusable() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { topMargin = statusBarHeightPx() + dpPx(64) }
        }

        // Compose 完整浏览器 chrome（自带暗色 MaterialTheme，宿主无需包装）。
        // 触摸激活由外层 root（TouchActivatingFrameLayout.dispatchTouchEvent）统一承担：
        // 父容器分发先于子 View，浮窗内任何按下（chrome/WebView）都会触发，无需包裹。
        val owner = OverlayLifecycleOwner()
        owner.performRestore()
        // ═══ P0 崩溃修复（2026-10-07 诊断包 crash-1791356720968）════════════
        // window recomposer 从「窗口根视图」（View.rootView）向上查
        // ViewTreeLifecycleOwner——旧实现只设在 ComposeView 自己身上，
        // ComposeView.onAttachedToWindow 时 root（TouchActivatingFrameLayout）
        // 的查找链不可达 → 立即抛
        // "ViewTreeLifecycleOwner not found from BrowserOverlay$TouchActivatingFrameLayout"。
        // 修复：root / host / ComposeView 全链挂同一 owner——任何查找起点
        // （root、host、ComposeView 及其子树）都能就近命中，行为不变。
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
        host.setViewTreeLifecycleOwner(owner)
        host.setViewTreeSavedStateRegistryOwner(owner)
        val compose = ComposeView(appContext).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                BrowserChrome(
                    gateway = chromeWiring.gateway,
                    config = ChromeConfig(),
                    modifier = Modifier.statusBarsPadding(),
                    handoffBanner = { HandoffBanner() },
                    onChromeHeightChanged = { px -> applyChromeHeaderHeight(px) },
                    pageSlot = {}, // WebView 由 host(FrameLayout) 承载，页面区不重复渲染
                )
            }
        }

        root.addView(host)
        root.addView(compose)

        rootView = root
        webViewHost = host
        composeView = compose
        lifecycleOwner = owner

        try {
            windowManager.addView(root, params)
            windowParams = params
            owner.onCreate()
            owner.onStart()
            owner.onResume()
            // 展开即对齐：标签结构 + 链式接管 + 快照
            chromeWiring.resync()
            rebindWebView(engine.activeWebView())
        } catch (e: Exception) {
            // 无悬浮窗权限或系统拒绝：静默降级，引擎照常后台工作。
            // 竞态修复：addView 已成功而后续步骤（lifecycle 驱动 Compose 重组 /
            // rebindWebView）抛异常时，若只清引用不摘窗，全屏 TYPE_APPLICATION_OVERLAY
            // 会永久滞留屏幕，且 doHide() 因 rootView==null 直接 return——用户再也无法
            // 关闭这个"僵尸窗口"。因此必须先 removeViewImmediate 兜底再清引用。
            runCatching { windowManager.removeViewImmediate(root) }
            runCatching { owner.onDestroy() }
            rootView = null
            webViewHost = null
            composeView = null
            lifecycleOwner = null
            windowParams = null
        }
    }

    private fun doHide() {
        val root = rootView ?: return
        // 从浮窗 detach WebView，交还引擎后台驱动
        detachWebView()
        lifecycleOwner?.let { owner ->
            runCatching {
                owner.onPause()
                owner.onStop()
                owner.onDestroy()
            }
        }
        try {
            windowManager.removeView(root)
        } catch (_: Exception) {
            // ignore
        }
        rootView = null
        webViewHost = null
        composeView = null
        lifecycleOwner = null
        windowParams = null
        windowFocusable = false
        chromeHeaderPx = 0
    }

    /** 把引擎 active WebView 挂到浮窗容器（接管期间人类真实交互）。 */
    private fun rebindWebView(view: WebView?) {
        val host = webViewHost ?: return
        if (view == null) {
            host.visibility = View.INVISIBLE
            return
        }
        if (view.parent === host) return
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        host.addView(view, lp)
        host.visibility = View.VISIBLE
        attachedWebView = view
    }

    /** 交还时把 WebView 从浮窗移除，回到后台无父状态。 */
    private fun detachWebView() {
        val wv = attachedWebView ?: return
        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
        attachedWebView = null
    }

    /** chrome 头部高度变化（地址栏/横幅/标签条增减）→ WebView 容器同步留白。 */
    private fun applyChromeHeaderHeight(px: Int) {
        if (px == chromeHeaderPx) return
        chromeHeaderPx = px
        val host = webViewHost ?: return
        val lp = host.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.topMargin = statusBarHeightPx() + px
        host.requestLayout()
    }

    // ───────── 接管横幅：显式握手的人机界面 ─────────

    @Composable
    private fun HandoffBanner() {
        val session by chromeWiring.engineSessionState.collectAsStateWithLifecycle()
        if (session != BrowserEngine.BrowserSessionState.WAITING_HUMAN) return
        val p = chromePalette()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(p.ok.copy(alpha = 0.14f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = p.ok,
                modifier = Modifier.height(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.browser_handoff_lock_hint), fontSize = 11.sp, color = p.textSecondary)
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { mainScope.launch { engine.completeHandoff() } },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 10.dp, vertical = 2.dp,
                ),
            ) { Text(stringResource(R.string.browser_handoff_collapse), fontSize = 11.sp) }
        }
        Button(
            onClick = { mainScope.launch { engine.completeHandoff() } },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = p.ok),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(stringResource(R.string.browser_handoff_complete), color = androidx.compose.ui.graphics.Color(0xFF06230F))
        }
    }

    // ───────── 窗口参数 ─────────

    private fun dpPx(dp: Int): Int =
        (dp * appContext.resources.displayMetrics.density).toInt()

    private fun statusBarHeightPx(): Int {
        val id = appContext.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) appContext.resources.getDimensionPixelSize(id) else dpPx(24)
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // 浮窗内可交互，浮窗外触摸透传（不抢外部事件）；
            // P0：默认不可聚焦 —— 不抢下方应用窗口的键盘焦点（IME 才能正常弹出），
            // 首次在浮窗内按下时由 [makeWindowFocusable] 临时移除 NOT_FOCUSABLE。
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // 地址栏编辑时软键盘随窗口 resize（overlay 窗口默认不调整，输入框会被键盘挡住）
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

    /**
     * 把浮窗切为可聚焦（页面/地址栏需要 IME 时）。仅首次触摸触发一次，
     * 失败静默（窗口已移除等时序）；隐藏时随窗口销毁自动复位。
     */
    private fun makeWindowFocusable() {
        if (windowFocusable) return
        val params = windowParams ?: return
        val root = rootView ?: return
        windowFocusable = true
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {
            // ignore：窗口可能正在被移除
        }
    }

    // ───────── 触摸激活容器（首次按下 → 窗口变可聚焦）─────────

    /** dispatchTouchEvent 级拦截：父容器分发先于子 View（ComposeView 为 final 不可继承，
     * 且 root 已覆盖全部子层级，chrome/WebView 无需各自包裹）。 */
    private class TouchActivatingFrameLayout(context: Context) : FrameLayout(context) {
        var onFirstTouch: (() -> Unit)? = null
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.action == MotionEvent.ACTION_DOWN) onFirstTouch?.invoke()
            return super.dispatchTouchEvent(ev)
        }
    }
}

/**
 * 浮窗进程级单例工厂（去 Hilt 后的宿主接法，改造 3）。
 * 宿主 Hilt Module 里 provide 即可：
 * ```
 * @Provides @Singleton
 * fun provideBrowserOverlay(
 *     @ApplicationContext ctx: Context,
 *     engine: BrowserEngine,
 *     wiring: ApexChromeWiring,
 * ): BrowserOverlay = BrowserOverlayFactory.get(ctx, engine, wiring)
 * ```
 */
object BrowserOverlayFactory {

    @Volatile
    private var instance: BrowserOverlay? = null

    fun get(
        context: Context,
        engine: BrowserEngine,
        wiring: ApexChromeWiring,
        visualHook: BrowserVisualHook? = null,
    ): BrowserOverlay =
        instance ?: synchronized(this) {
            instance ?: BrowserOverlay.create(context, engine, wiring, visualHook)
                .also { instance = it }
        }

    /** 仅测试用：重置单例。 */
    @Synchronized
    fun reset() {
        instance = null
    }
}
