package com.apex.browser.engine

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import com.apex.browser.core.BrowserScript
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * EngineDisplayController —— 页面显示控制（v1.7.0）：文字缩放 / 夜间模式 /
 * 清除浏览数据。
 *
 * 为什么是独立控制器而不是 [BrowserEngine] 的方法：引擎主文件已贴满
 * 1200 非注释行预算（GATE 1），且「显示偏好」是有状态（textZoom 数值 /
 * forceDark 开关）、需要跨页持久的关注点——与引擎的无状态动作空间
 *（click/input/select…）不同质。接线方式与 [BookmarkStore] 一致：
 * 单例工厂 + 宿主 DI provide；chrome 层经 Gateway 的 additive 成员消费。
 *
 * 生效模型（三个入口各司其职）：
 * 1. **切换时重放**：订阅读者 [BrowserEngine.ActiveTabListener]——标签
 *    切换/新建/崩溃重建后新 WebView 不继承 settings，必须重放当前偏好；
 * 2. **导航后重注**：JS 上下文随导航重置（forceDark 的 style 节点随之
 *    消失）——chrome 侧在页面加载完成时调 [reapplyForCurrentPage]
 *   （PageStateSink.onPageFinished 钩子，见 wiring 接线）；
 * 3. **即时生效**：[setTextZoom] / [setForceDark] 直接对当前活动页执行。
 *
 * textZoom 走 WebSettings（native，对所有内容生效含 iframe）；
 * forceDark 走 [BrowserScript.FORCE_DARK_JS] 注入（算法反色，无
 * androidx.webkit 依赖，取舍见其 KDoc）。
 */
class EngineDisplayController private constructor(
    private val engine: BrowserEngine,
) {

    /** 显示偏好快照（UI 的单一事实源）。 */
    data class DisplaySettings(
        val textZoomPercent: Int = DEFAULT_TEXT_ZOOM,
        val forceDark: Boolean = false,
    ) {
        val textZoomRatio: Float get() = textZoomPercent / 100f

        companion object {
            const val DEFAULT_TEXT_ZOOM = 100
            const val MIN_TEXT_ZOOM = 50
            const val MAX_TEXT_ZOOM = 200
        }
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            Log.w(TAG, "display-controller task failed", e)
        }
    )

    private val _settings = MutableStateFlow(DisplaySettings())
    /** 当前显示偏好（UI 开关与滑杆的数据源）。 */
    val settings: StateFlow<DisplaySettings> = _settings.asStateFlow()

    init {
        // 标签切换/新建/崩溃重建 → 新 WebView 重放偏好（settings 不随 WebView 走）。
        // ActiveTabListener 是普通 interface（非 fun interface，无 SAM 转换）→ object 表达式。
        engine.addActiveTabListener(object : BrowserEngine.ActiveTabListener {
            override fun onActiveTabChanged(webView: WebView?) {
                webView?.let { applyTo(it) }
            }
        })
    }

    /* ---------------- 文字缩放 ---------------- */

    /**
     * 设置文字缩放（50..200，100 = 原生）。跨页保持（[applyTo] 重放），
     * 立即作用于当前活动页。
     */
    fun setTextZoom(percent: Int) {
        val clamped = percent.coerceIn(DisplaySettings.MIN_TEXT_ZOOM, DisplaySettings.MAX_TEXT_ZOOM)
        _settings.value = _settings.value.copy(textZoomPercent = clamped)
        engine.activeWebView()?.let { applyTo(it) }
    }

    /* ---------------- 夜间模式 ---------------- */

    /** 开关夜间模式（算法反色）。导航后由 chrome 侧 [reapplyForCurrentPage] 重注。 */
    fun setForceDark(enabled: Boolean) {
        _settings.value = _settings.value.copy(forceDark = enabled)
        injectForceDark(enabled)
    }

    /**
     * 页面加载完成后的重放入口（chrome PageStateSink.onPageFinished 调）。
     *
     * 幂等：FORCE_DARK_JS 自带幂等守卫；textZoom 重放是幂等的 settings 赋值。
     */
    fun reapplyForCurrentPage() {
        engine.activeWebView()?.let { applyTo(it) }
        if (_settings.value.forceDark) injectForceDark(true)
    }

    /* ---------------- 清除浏览数据 ---------------- */

    /**
     * 清除浏览数据。异步完成（CookieManager.removeAllCookies 带回调），
     * [onCleared] 在完成时回调一次（UI 轻提示）。
     *
     * 范围口径（诚实文档）：
     * - cookies：全局 CookieManager（**所有站点**的登录态——与桌面浏览器
     *   「清除 Cookie」语义一致，用户应有此预期）；
     * - caches：全部标签 `clearCache(true)`（引擎 performMaintenance）；
     * - histories：WebView 原生前进/后退历史。**不含**引擎的地址栏联想
     *   历史（recentHistory 的 ArrayDeque 无清空 API——已知取舍，见
     *   README v1.7.0 已知取舍节）。
     */
    fun clearBrowsingData(
        clearCookies: Boolean,
        clearCaches: Boolean,
        clearHistories: Boolean,
        onCleared: (() -> Unit)? = null,
    ) {
        var pending = 0
        var completed = 0
        val maybeDone = {
            if (pending > 0 && completed >= pending) onCleared?.invoke()
        }
        if (clearCookies) {
            pending++
            val cm = runCatching { CookieManager.getInstance() }.getOrNull()
            if (cm == null) {
                completed++
                maybeDone()
            } else {
                runCatching {
                    cm.removeAllCookies {
                        completed++
                        maybeDone()
                    }
                    cm.removeSessionCookies(null)
                    cm.flush()
                }.onFailure {
                    completed++
                    maybeDone()
                }
            }
        }
        if (clearCaches || clearHistories) {
            // performMaintenance 同时清 cache + native history；分项请求时
            // 只能整组执行（引擎 API 粒度如此，文档化而非夸大分项能力）
            engine.performMaintenance()
        }
        if (pending == 0) {
            // 无 Cookie 待清 → 同步完成
            onCleared?.invoke()
        }
    }

    /* ---------------- 内部 ---------------- */

    /** 把当前偏好应用到指定 WebView（切换/新建/重放共用）。 */
    private fun applyTo(wv: WebView) {
        runCatching {
            wv.settings.textZoom = _settings.value.textZoomPercent
        }
    }

    private fun injectForceDark(enabled: Boolean) {
        val js = if (enabled) BrowserScript.FORCE_DARK_JS else BrowserScript.FORCE_DARK_OFF_JS
        scope.launch {
            runCatching { engine.executeJavaScript(js) }
        }
    }

    companion object {
        private const val TAG = "EngineDisplayController"

        /**
         * 进程级单例工厂（chrome wiring 与宿主 DI 共享同一实例——偏好
         * 状态唯一）。与 [BookmarkStore] / [BrowserEngineFactory] 同纪律。
         */
        @Volatile
        private var instance: EngineDisplayController? = null

        fun get(engine: BrowserEngine): EngineDisplayController =
            instance ?: synchronized(this) {
                instance ?: EngineDisplayController(engine).also { instance = it }
            }

        /** 仅测试用：重置单例。 */
        @Synchronized
        fun reset() {
            instance = null
        }
    }
}

/** 单例工厂的独立宿主（保持文件组织习惯）。 */
object EngineDisplayControllerFactory {

    fun get(engine: BrowserEngine): EngineDisplayController =
        EngineDisplayController.get(engine)

    @Synchronized
    fun reset() = EngineDisplayController.reset()
}
