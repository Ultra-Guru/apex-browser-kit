package com.apex.browser.chrome

import java.util.concurrent.CopyOnWriteArraySet

/*
 * InAppHostingArbiter —— 进程级「活动 WebView 承载权」仲裁点（v1.5.0）。
 *
 * 背景：库内可视层原本只有系统悬浮窗（BrowserOverlay / LiveViewOverlay），
 * 都需要 SYSTEM_ALERT_WINDOW 特殊权限；v1.5.0 新增 [InAppChromeHost] 允许
 * 宿主把完整浏览器嵌进 **App 自己的窗口**（Activity 内组合 BrowserChrome +
 * WebView 容器，零悬浮窗权限）。同一时刻一个 WebView 只能有一个父容器 ——
 * 三个承载面（in-app 容器 / 全屏接管浮窗 / 实时页面卡片）需要一个裁决点：
 *
 * - **in-app 承载激活**（宿主浏览器页在前台且 RESUMED）：
 *   - [BrowserOverlay]：WAITING_HUMAN 不再自动展开（in-app chrome 就是人的 UI）；
 *   - [LiveViewOverlay]：可见卡片转 GHOST（页面在 App 内直接可见，卡片不抢
 *     WebView，悬浮窗坐标宿主让位给 in-app 容器）；
 * - **in-app 承载退场**（离开页面 / Activity ON_PAUSE）：各悬浮窗按既有
 *   状态机恢复（人工接管 → 全屏浮窗展开；Agent 驾驶 → 实时卡片弹出）。
 *
 * 线程契约：claim/release 由 [InAppChromeHost] 在主线程调用（Compose 生命周期
 * 回调）；通知是同步派发的（本仓可视层监听者均已在主线程，主线程同步 =
 * 零调度延迟、时序确定）。[isInAppHosting] 带 @Volatile 供任意线程快速读
 * （Agent 工具线程只读不写）。违反线程契约不会破坏结构（CopyOnWrite 快照
 * + runCatching 兜底），只是通知时序不保证。
 *
 * 纯 JVM 无 Android 依赖 —— 契约由 InAppHostingArbiterTest 锁定。
 */
object InAppHostingArbiter {

    /** 承载权变化监听（两个悬浮窗 + 宿主可订阅；实现应自线程安全/主线程消化）。 */
    fun interface Listener {
        /**
         * @param active true = in-app 容器获得承载权（悬浮窗让位）；
         *               false = in-app 退场（悬浮窗按状态机恢复）
         */
        fun onInAppHostingChanged(active: Boolean)
    }

    @Volatile
    private var inApp = false

    private val listeners = CopyOnWriteArraySet<Listener>()

    /** 当前是否由宿主 App 内嵌浏览器承载活动 WebView。 */
    val isInAppHosting: Boolean get() = inApp

    /** 注册承载权监听（幂等）。 */
    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    /** 注销承载权监听（幂等）。 */
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** in-app 容器进场（主线程，[InAppChromeHost.attach]/[InAppChromeHost.resume]）。 */
    fun claim() = transition(true)

    /** in-app 容器退场（主线程，[InAppChromeHost.detach]/[InAppChromeHost.pause]）。 */
    fun release() = transition(false)

    /** 仅测试用：复位单例状态（测试间隔离）。 */
    @Synchronized
    fun resetForTest() {
        inApp = false
        listeners.clear()
    }

    private fun transition(next: Boolean) {
        if (inApp == next) return
        inApp = next
        // 快照遍历：监听者可能在通知回调里注销自己（CopyOnWrite 迭代器语义）
        listeners.toList().forEach { listener ->
            runCatching { listener.onInAppHostingChanged(next) }
        }
    }
}
