package com.apex.browser.core

import kotlinx.serialization.json.Json

/**
 * 把浏览器注入的 JS 抓取结果解析成 Agent 友好的 [PageSnapshot]。
 *
 * 接受两种回传形态（[BrowserScript.snapshotJs] 现产后者，[RawSnapshotEnvelope]）：
 * - `{v, total, truncated, elements}` —— 附带总数与截断标记，用于如实告知模型
 *   「页面里还有更多元素」；
 * - 裸元素数组 —— 旧版注入脚本，以及任何自行构造 raw JSON 的消费方（仍受支持）。
 *
 * 设计要点（对标并超越 Operit）：
 * 1. 每个元素取 JS 注入的语义哈希 `data-apex-hash` 作为 [DomElement.ref]。**缺失时留空
 *    而非编造** —— ref 是定位的唯一主键，编造出的 ref 永远解析不到，会白耗重试并打开熔断器。
 * 2. [buildSummary] 生成面向 LLM 的紧凑文本树，按 token 预算裁剪，预算优先给
 *    「可交互 > 浅层 > 有标签」的元素，输出顺序仍按 [DomElement.bid] 保持页面阅读顺序。
 * 3. 保留 [DomElement.rect] / [DomElement.isVisible]，使工具既能 DOM 级点击，也能物理触摸兜底。
 *
 * 纯 Kotlin（无 Android 依赖），可在 JVM 单测中验证 DOM 摘要压缩与截断上报行为。
 */
object DomParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 交互标签：这些标签天然可点击 / 可输入 */
    private val INTERACTIVE_TAGS = setOf(
        "A", "BUTTON", "INPUT", "SELECT", "TEXTAREA", "SUMMARY", "DETAILS"
    )

    /** 视为「链接/按钮」语义、需要给 label 的 role */
    private val CLICKABLE_ROLES = setOf(
        "button", "link", "menuitem", "tab", "option", "checkbox", "radio", "switch"
    )

    /** 表单类标签（用于 FORM_FIELDS 剪枝策略） */
    private val FORM_TAGS = setOf("INPUT", "SELECT", "TEXTAREA")
    private val FORM_ROLES = setOf("checkbox", "radio", "switch", "textbox", "searchbox")

    /**
     * 快照剪枝策略（#19/#20 可编程剪枝）：Agent 在 [browser_snapshot] 时指定，
     * 控制保留哪些元素，避免把整页灌进 prompt。默认 [INTERACTIVE_ONLY] 即原有行为。
     */
    enum class SnapshotStrategy {
        /** 仅交互元素（默认，原有行为） */
        INTERACTIVE_ONLY,
        /** 仅表单相关（输入/下拉/勾选/单选），用于填表场景 */
        FORM_FIELDS,
        /** 仅含文本的内容节点（标题/段落/链接文本），用于纯阅读/抽取场景 */
        CONTENT_SUMMARY
    }

    /**
     * @param rawJson 由 [com.apex.browser.core.BrowserScript.SNAPSHOT_JS] 注入执行后回传的 JSON 数组
     * @param url / title / scrollY / scrollHeight / viewportHeight 由 WebView 宿主在调用时填入
     * @param tokenBudget [buildSummary] 的字符预算（粗略按字符估算 token）
     * @param strategy 剪枝策略，见 [SnapshotStrategy]
     */
    fun parse(
        rawJson: String,
        url: String,
        title: String,
        scrollY: Int,
        scrollHeight: Int,
        viewportHeight: Int,
        tokenBudget: Int = 1600,
        strategy: SnapshotStrategy = SnapshotStrategy.INTERACTIVE_ONLY,
    ): PageSnapshot {
        val envelope = runCatching {
            json.decodeFromString<RawSnapshotEnvelope>(rawJson)
        }.getOrNull()
        // 同时接受两种形态：新版注入脚本回传 {elements,total,truncated} 信封；
        // 旧版（以及任何自行构造 raw JSON 的消费方）回传裸数组。
        val raw: List<RawDomElement> = envelope?.elements
            ?: runCatching { json.decodeFromString<List<RawDomElement>>(rawJson) }
                .getOrDefault(emptyList())

        val interactive = mutableListOf<DomElement>()
        var bid = 0
        for (r in raw) {
            if (!matchesStrategy(r, strategy)) continue
            val isInteractive = r.isInteractive || isInteractiveByTagOrRole(r)
            if (!isInteractive && strategy == SnapshotStrategy.INTERACTIVE_ONLY) continue
            if (strategy == SnapshotStrategy.INTERACTIVE_ONLY &&
                (r.text ?: "").isBlank() && !hasMeaningfulAttr(r)) continue
            bid++
            // ref 是**唯一**的定位主键：click/input/select 一律经 data-apex-hash 反查元素。
            // 缺失时不得编造 —— 旧实现回退成 "r$bid"，形似合法语义哈希却永远查不到，
            // 于是白耗 3 次重试并**打开熔断器**，一个坏元素锁死整段会话。
            // 无 ref 的元素对 Agent 不可操作，据实留空并在摘要中说明。
            val ref = r.attributes["data-apex-hash"]?.takeIf { it.isNotBlank() } ?: ""
            interactive += DomElement(
                bid = bid,
                ref = ref,
                tag = r.tag.lowercase(),
                text = (r.text ?: "").trim().take(120),
                label = buildLabel(r),
                attributes = r.attributes.filter { it.key.lowercase() in KEEP_ATTR },
                rect = r.rect,
                isVisible = r.isVisible,
                isInteractive = isInteractive,
                depth = r.depth,
                childCount = r.childCount,
            )
        }

        // 截断诚实性：注入脚本按 SNAPSHOT_MAX_ELEMENTS 硬上限截断。若不告知，
        // 模型会把「看到的 50 个」当成「页面全部」，据此规划动作必然踩空。
        val truncated = envelope?.truncated ?: false
        val totalCandidates = envelope?.total ?: raw.size

        val summary = buildSummary(interactive, tokenBudget, truncated, totalCandidates)
        return PageSnapshot(
            url = url,
            title = title,
            scrollY = scrollY,
            scrollHeight = scrollHeight,
            viewportHeight = viewportHeight,
            interactiveCount = interactive.size,
            domSummary = summary,
            interactiveElements = interactive,
            truncated = truncated,
            totalCandidateCount = totalCandidates,
        )
    }

    /** 按剪枝策略判断原始元素是否进入候选集（#19/#20） */
    private fun matchesStrategy(r: RawDomElement, strategy: SnapshotStrategy): Boolean {
        return when (strategy) {
            SnapshotStrategy.INTERACTIVE_ONLY -> true
            SnapshotStrategy.FORM_FIELDS ->
                r.tag.uppercase() in FORM_TAGS || r.attributes["role"]?.lowercase() in FORM_ROLES
            SnapshotStrategy.CONTENT_SUMMARY ->
                (r.text?.isNotBlank() == true) ||
                    r.attributes.containsKey("href") || r.attributes.containsKey("aria-label")
        }
    }

    private fun isInteractiveByTagOrRole(r: RawDomElement): Boolean {
        if (r.tag.uppercase() in INTERACTIVE_TAGS) return true
        val role = r.attributes["role"]?.lowercase()
        if (role in CLICKABLE_ROLES) return true
        // 带 onclick 或 cursor:pointer 样式的元素也视为可点击
        if (r.attributes.containsKey("onclick")) return true
        return false
    }

    private fun hasMeaningfulAttr(r: RawDomElement): Boolean =
        r.attributes.containsKey("href") || r.attributes.containsKey("name") ||
            r.attributes.containsKey("aria-label") || r.attributes.containsKey("placeholder") ||
            r.attributes.containsKey("title")

    private fun buildLabel(r: RawDomElement): String {
        val aria = r.attributes["aria-label"] ?: r.attributes["title"]
        val placeholder = r.attributes["placeholder"]
        val text = r.text?.trim()
        val visible = aria ?: placeholder ?: text
        val tagHint = when {
            r.tag.equals("A", true) -> "链接"
            r.tag.equals("INPUT", true) -> when (r.attributes["type"]?.lowercase()) {
                "text", "search", "email", "password" -> "输入框"
                "checkbox" -> "勾选框"
                "radio" -> "单选"
                "submit" -> "提交按钮"
                else -> "输入"
            }
            r.tag.equals("BUTTON", true) -> "按钮"
            r.tag.equals("SELECT", true) -> "下拉"
            r.tag.equals("TEXTAREA", true) -> "文本框"
            else -> r.attributes["role"]?.let { "[$it]" } ?: r.tag
        }
        return "$tagHint ${visible ?: ""}".trim()
    }

    /**
     * 生成紧凑文本树。策略：
     * - 预算内优先保留**可操作**元素：可交互 > 浅层（页面主控件常在浅层）> 有可读标签；
     *   同优先级按文档顺序（[DomElement.bid]）稳定排序。
     * - 输出顺序仍按 [DomElement.bid]，避免为省 token 而打乱页面的自然阅读顺序。
     * - 超预算被折叠、或因注入脚本硬上限而未进入快照的元素，据实计数告知模型。
     *
     * 修复说明：KDoc 一直声称「优先裁剪深层且文本信息量低的元素」，但实现只是按
     * 顺序截断 —— [DomElement.depth] 从未被使用，且注入脚本恒把 depth 写死 0。
     * 结果是超预算时被丢掉的往往是页面末尾的次要控件，而顶部的导航/搜索框等
     * 关键控件反而可能因位置靠后而消失。现两边都补上：脚本计算真实 depth，
     * 此处按优先级决定谁进预算。
     */
    private fun buildSummary(
        elements: List<DomElement>,
        tokenBudget: Int,
        truncatedByScript: Boolean,
        totalCandidates: Int,
    ): String {
        val sb = StringBuilder()
        val unaddressable = elements.count { it.ref.isEmpty() }
        sb.append("⊕ 页面可交互元素（共 ${elements.size} 个")
        if (truncatedByScript) {
            sb.append("；⚠ 页面实际匹配 $totalCandidates 个，已达单次抓取上限，仅返回前一部分")
        }
        if (unaddressable > 0) {
            sb.append("；其中 $unaddressable 个无可用 ref，仅供参考不可点击")
        }
        sb.appendLine("）：")

        val ranked = elements.sortedWith(
            compareByDescending<DomElement> { it.isInteractive }
                .thenBy { it.depth }
                .thenByDescending { it.label.isNotBlank() }
                .thenBy { it.bid },
        )
        // v1.8.0 提速：单行渲染按 bid 缓存一次（选择循环与输出循环各用一遍，
        // 旧实现重复计算 lineOf —— 大快照下是纯浪费）。
        val lineByBid = HashMap<Int, String>(elements.size * 2)
        elements.forEach { lineByBid[it.bid] = lineOf(it) }
        // 先按优先级选出预算内可容纳的集合，再按 bid 渲染。
        val admitted = HashSet<Int>()
        var total = sb.length
        for (e in ranked) {
            val cost = lineByBid.getValue(e.bid).length + 1
            if (total + cost <= tokenBudget) {
                admitted += e.bid
                total += cost
            }
        }
        val hidden = elements.size - admitted.size
        for (e in elements) {
            if (e.bid in admitted) sb.appendLine(lineByBid[e.bid])
        }

        if (hidden > 0) {
            // 意图：这是直接进 prompt 的模型可见文案，绝不能让模型去调用不存在的工具。
            // 旧实现写「用 browser_dump 查看全部」，而浏览器工具集里并无 browser_dump
            // （真实工具是宿主侧的 browser_debug_dump，且它导出的是 trace 而非元素）。
            // 折叠的元素仍完整保留在 interactiveElements 中，故据实说明即可。
            sb.appendLine("  …折叠 $hidden 个低优先级元素（完整列表见 interactiveElements，可调大 token_budget 重抓）")
        }
        return sb.toString().trimEnd()
    }

    /**
     * 单行渲染。
     *
     * 有 [DomElement.ref] 时以 ref 为定位主键（模型据此回传操作）；无 ref 时退化为
     * `bid` 并标注不可操作，避免模型把展示序号误当作可用的 ref 回传。
     */
    private fun lineOf(e: DomElement): String {
        val rectHint = if (!e.isVisible) " (不可见)" else ""
        return if (e.ref.isEmpty()) {
            "  [${e.bid}·不可操作] ${e.label}$rectHint"
        } else {
            "  [${e.ref}] ${e.label}$rectHint"
        }
    }

    private val KEEP_ATTR = setOf(
        "href", "name", "type", "placeholder", "value", "aria-label", "title", "role", "alt", "id"
    )
}
