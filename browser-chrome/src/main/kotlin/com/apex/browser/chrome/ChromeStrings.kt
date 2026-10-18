package com.apex.browser.chrome

/*
 * ChromeStrings —— Chrome 逻辑层用户可见文案的注入出口（v1.2.0）。
 *
 * 为什么存在（README「已知取舍 #1」的消解）：
 * chrome 层有三处文案产生于**逻辑层**而非 Compose 上下文，`stringResource`
 * 在那里不可达（它必须是组合作用域内的 @Composable 调用）：
 *   1. [BrowserChromeController] 的 6 条 snackbar（关标签/撤销/关其他/关全部/
 *      桌面版切换 ×2）—— 从状态机协程里直接 emit；
 *   2. [SuggestionsBuilder] 的 4 条联想副标题（剪贴板/网址/切换标签/搜索）——
 *      object 纯函数，连 Context 都摸不到；
 *   3. [ApexChromeWiring.choiceLabel] 的 3 条 JS 弹窗按钮审计标签
 *      （确认/拒绝/关闭）—— 回写 engine.lastDialog 的字符串拼装。
 * v1.0.0 因此把它们硬编码成中文，非中文宿主无法介入。本接口把「文案取值」
 * 从「逻辑」中剥离：库依旧零资源依赖、零 Context 强绑，宿主按语言（或任意
 * 品牌/语气）自供实现即可。
 *
 * 设计约束（与本库「零 DI、零 Context 强绑」哲学一致）：
 *   - 纯 Kotlin 接口，无 Android 依赖，不进资源系统 —— 逻辑层可达性优先；
 *   - 接入方式全部是**带默认值的尾参**（[BrowserChromeController]、
 *     [SuggestionsBuilder.build]、[ApexChromeWiring]、[ApexChromeWiringFactory.get]），
 *     现有调用点零改动，行为零变化（[DEFAULT] 即 [ZhChromeStrings]，
 *     文案与 v1.1.0 硬编码逐字一致）；
 *   - Compose UI 层文案继续走 res/values + values-en（`browser_` 前缀强制），
 *     两套机制各管一层：UI 层跟系统语言，逻辑层跟注入实现。
 *
 * 13 条成员的契约由 ChromeStringsTest 锁定（中文逐字一致 / 英文非空 /
 * 成员计数防漂移）。
 */
interface ChromeStrings {

    /* ---------------- snackbar（BrowserChromeController） ---------------- */

    /** 关闭标签后的轻提示（宽限期内可撤销）。 */
    val snackTabClosed: String

    /** 「已关闭标签」的撤销动作按钮。 */
    val snackUndo: String

    /** 关闭除当前外全部标签后的轻提示。 */
    val snackOthersClosed: String

    /** 关闭全部标签后的轻提示。 */
    val snackAllClosed: String

    /** 切到桌面版网站后的轻提示。 */
    val snackDesktopModeOn: String

    /** 切回移动版网站后的轻提示。 */
    val snackDesktopModeOff: String

    /* ---------------- 联想副标题（SuggestionsBuilder） ---------------- */

    /** 空输入时剪贴板直达项的副标题。 */
    val suggestionClipboardHint: String

    /** 输入本身可解析为网址时直达项的副标题。 */
    val suggestionUrlLabel: String

    /** 搜索兜底项的副标题。 */
    val suggestionSearchLabel: String

    /** 已打开标签命中项的副标题，须包含 [host] 供用户辨认目标站点。 */
    fun tabSwitchHint(host: String): String

    /* ---------------- JS 弹窗按钮审计标签（ApexChromeWiring） ---------------- */

    /** JS confirm 走「确认」分支时的审计标签（回写 engine.lastDialog）。 */
    val jsDialogConfirmLabel: String

    /** JS confirm 走「拒绝」分支时的审计标签。 */
    val jsDialogDenyLabel: String

    /** JS 弹窗走「关闭」分支时的审计标签。 */
    val jsDialogDismissLabel: String

    companion object {
        /**
         * 默认实现：中文（[ZhChromeStrings]，与 v1.1.0 硬编码逐字一致）。
         * 写成 getter 而非直接初始化 —— 取值永远回到约定的来源对象，
         * 也避免任何字段级改写让 DEFAULT 悄悄漂移。
         */
        val DEFAULT: ChromeStrings get() = ZhChromeStrings
    }
}

/** 中文文案（默认）。逐字对齐 v1.1.0 硬编码值 —— 注入机制落地必须行为零变化。 */
object ZhChromeStrings : ChromeStrings {
    override val snackTabClosed: String = "已关闭标签"
    override val snackUndo: String = "撤销"
    override val snackOthersClosed: String = "已关闭其他标签"
    override val snackAllClosed: String = "已关闭全部标签"
    override val snackDesktopModeOn: String = "已切换桌面版网站"
    override val snackDesktopModeOff: String = "已切换回移动版"
    override val suggestionClipboardHint: String = "剪贴板 · 粘贴即走"
    override val suggestionUrlLabel: String = "网址"
    override val suggestionSearchLabel: String = "搜索"
    override fun tabSwitchHint(host: String): String = "切换 · $host"
    override val jsDialogConfirmLabel: String = "确认"
    override val jsDialogDenyLabel: String = "拒绝"
    override val jsDialogDismissLabel: String = "关闭"
}

/**
 * 英文文案。词汇与 res/values-en/strings.xml 的 UI 层镜像保持同一套语感
 * （Paste and go / URL / Search / Confirm / Deny / Close …）。
 */
object EnglishChromeStrings : ChromeStrings {
    override val snackTabClosed: String = "Tab closed"
    override val snackUndo: String = "Undo"
    override val snackOthersClosed: String = "Other tabs closed"
    override val snackAllClosed: String = "All tabs closed"
    override val snackDesktopModeOn: String = "Switched to desktop site"
    override val snackDesktopModeOff: String = "Switched back to mobile site"
    override val suggestionClipboardHint: String = "Clipboard · Paste and go"
    override val suggestionUrlLabel: String = "URL"
    override val suggestionSearchLabel: String = "Search"
    override fun tabSwitchHint(host: String): String = "Switch · $host"
    override val jsDialogConfirmLabel: String = "Confirm"
    override val jsDialogDenyLabel: String = "Deny"
    override val jsDialogDismissLabel: String = "Close"
}
