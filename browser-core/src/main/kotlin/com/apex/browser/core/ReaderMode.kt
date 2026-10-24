package com.apex.browser.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * ReaderMode —— 阅读模式数据模型与解析（v1.7.0）。
 *
 * 数据流：
 *   [BrowserScript.readerDocumentJs]（页面侧采集）
 *     → JSON 文本（transport：title/byline/siteName/publishedTime/blocks[]/toc[]）
 *     → [ReaderParser.parse]（本文件，纯 JVM）
 *     → [ReaderDocument]（chrome ReaderSheet 渲染 / Agent BrowserRead 消费）
 *
 * 为什么不复用 [BrowserScript.extractContentJs] 的 article 模式：
 * article 返回**拍平的纯文本**（20k 字符截断），够 Agent「读内容」但不
 * 够人用——阅读模式需要**结构**（标题层级 / 引用块 / 代码块 / 图片替代
 * 文本 / 目录锚点）与**排版元数据**（byline / 发布时间 / 预计阅读时长）。
 * 一次采集两份消费：[ReaderParser.plainText] 把 [ReaderDocument] 拍回纯
 * 文本给 Agent，互为镜像不重复采集。
 *
 * 纯 JVM 纪律：解析在 `:browser-core`（与 [PageClassifier] 同款下沉），
 * chrome / engine 两侧都不持有解析逻辑。
 */

/** 阅读块类型（渲染分发的依据；与 readerDocumentJs 的 kind 值一一对应）。 */
enum class ReaderBlockKind(val wire: String) {
    HEADING("heading"),
    PARAGRAPH("paragraph"),
    QUOTE("quote"),
    CODE("code"),
    IMAGE("image"),
    LIST_ITEM("list_item");

    companion object {
        fun fromWire(value: String?): ReaderBlockKind =
            entries.firstOrNull { it.wire == value } ?: PARAGRAPH
    }
}

/** 单个阅读块。文本统一已 trim + 空白折叠（采集侧保证，解析侧防御）。 */
data class ReaderBlock(
    val kind: ReaderBlockKind,
    /** HEADING 的层级（1..6）；其余块无意义（0）。 */
    val level: Int = 0,
    val text: String,
    /** IMAGE 块的替代文本（空串 = 无 alt，渲染为「无描述图片」占位）。 */
    val alt: String = "",
    /** IMAGE 块的 src（仅展示用摘要：协议+host+文件名，不整串塞进 UI）。 */
    val srcSummary: String = "",
    /** 块在原文档中的序号（目录锚点：点目录跳到对应块）。 */
    val index: Int = 0,
)

/** 目录条目（由 HEADING 块聚合）。 */
data class ReaderChapter(
    val level: Int,
    val title: String,
    /** 指向 [ReaderDocument.blocks] 的下标（[ReaderBlock.index]）。 */
    val blockIndex: Int,
)

/** 阅读文档（一次采集的完整结构化结果）。 */
data class ReaderDocument(
    val url: String,
    val title: String,
    val siteName: String = "",
    val byline: String = "",
    val publishedTime: String = "",
    val blocks: List<ReaderBlock> = emptyList(),
    val chapters: List<ReaderChapter> = emptyList(),
    /** 采集时间戳（新鲜度判定）。 */
    val capturedAtMs: Long = 0L,
) {
    /** 估算阅读时长（分钟，向上取整；中文 400 字/分、拉丁 200 词/分的折中密度）。 */
    val readingMinutes: Int
        get() {
            val chars = blocks.sumOf { it.text.length }
            return ((chars + READING_RATE_CPM - 1) / READING_RATE_CPM).coerceAtLeast(1)
        }

    val isMeaningful: Boolean
        get() = blocks.count { it.kind != ReaderBlockKind.IMAGE && it.text.isNotBlank() } >= MIN_MEANINGFUL_BLOCKS

    companion object {
        private const val READING_RATE_CPM = 350
        private const val MIN_MEANINGFUL_BLOCKS = 3
    }
}

/**
 * 阅读文档解析器（纯函数）。
 *
 * 输入是 [BrowserScript.readerDocumentJs] 回传的 JSON **内层**文本
 *（调用方负责剥掉 evaluateJavascript 的 JSON 编码外壳，与
 * [DomParser.parse] 的 rawJson 契约一致）。
 *
 * 防御性契约：
 * - 任何畸形输入返回 null（调用方据此提示「本页不适合阅读模式」），
 *   绝不抛错——阅读模式是增强体验，不是关键路径；
 * - 数值字段缺失/类型错位取默认值；块数组超长截断（采集侧有 400 块
 *   上限，解析侧再守一次——JSON 信不过原则）。
 */
object ReaderParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 采集侧的单块文本截断（与 readerDocumentJs 的 slice 上限一致）。 */
    private const val MAX_BLOCK_TEXT = 4000

    fun parse(rawJson: String?): ReaderDocument? {
        if (rawJson.isNullOrBlank() || rawJson == "null") return null
        val obj = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return null
        val url = str(obj, "url")
        val title = str(obj, "title").ifBlank { UrlCleaner.hostOf(url) }
        val blocks = parseBlocks(obj)
        if (blocks.isEmpty()) return null
        val chapters = blocks
            .withIndex()
            .filter { it.value.kind == ReaderBlockKind.HEADING && it.value.text.isNotBlank() }
            .map { (i, b) -> ReaderChapter(level = b.level, title = b.text, blockIndex = i) }
        return ReaderDocument(
            url = url,
            title = title,
            siteName = str(obj, "siteName"),
            byline = str(obj, "byline"),
            publishedTime = str(obj, "publishedTime"),
            blocks = blocks,
            chapters = chapters,
            capturedAtMs = System.currentTimeMillis(),
        )
    }

    private fun parseBlocks(obj: JsonObject): List<ReaderBlock> {
        val arr = runCatching { obj["blocks"]?.jsonArray }.getOrNull() ?: return emptyList()
        val out = ArrayList<ReaderBlock>(arr.size)
        for ((i, el) in arr.withIndex()) {
            if (out.size >= MAX_BLOCKS) break
            val b = runCatching { el.jsonObject }.getOrNull() ?: continue
            val kind = ReaderBlockKind.fromWire(str(b, "kind"))
            val text = str(b, "text").take(MAX_BLOCK_TEXT)
            if (kind != ReaderBlockKind.IMAGE && text.isBlank()) continue
            out.add(
                ReaderBlock(
                    kind = kind,
                    level = int(b, "level"),
                    text = text,
                    alt = str(b, "alt").take(300),
                    srcSummary = summarizeSrc(str(b, "src")),
                    index = i,
                )
            )
        }
        return out
    }

    /** 图片 src 的展示摘要：去 query、只留路径末段（列表里塞整串 src 是排版灾难）。 */
    private fun summarizeSrc(src: String): String {
        if (src.isBlank()) return ""
        val noQuery = src.substringBefore('?')
        val fileName = noQuery.substringAfterLast('/').take(60)
        val host = UrlCleaner.hostOf(src)
        return if (fileName.isBlank()) host else "$host · $fileName"
    }

    /**
     * 拍平为纯文本（Agent 消费形态；带目录则先输出目录）。
     *
     * 与 [BrowserScript.extractContentJs] 的 article 文本不同点：
     * 块间以空行分隔、标题带 Markdown 级前缀、引用带 > 前缀——LLM 的
     * 结构感知显著优于无分隔大段文本。
     */
    fun plainText(doc: ReaderDocument, includeOutline: Boolean = true): String = buildString {
        if (includeOutline && doc.chapters.isNotEmpty()) {
            appendLine("## 目录")
            doc.chapters.forEach { ch ->
                appendLine("${"  ".repeat((ch.level - 1).coerceIn(0, 4))}- ${ch.title}")
            }
            appendLine()
        }
        appendLine("# ${doc.title}")
        if (doc.byline.isNotBlank()) appendLine("作者：${doc.byline}")
        if (doc.publishedTime.isNotBlank()) appendLine("发布：${doc.publishedTime}")
        if (doc.siteName.isNotBlank()) appendLine("来源：${doc.siteName}")
        appendLine()
        doc.blocks.forEach { b ->
            when (b.kind) {
                ReaderBlockKind.HEADING -> appendLine("${"#".repeat(b.level.coerceIn(1, 6))} ${b.text}").let { }
                ReaderBlockKind.PARAGRAPH -> appendLine(b.text)
                ReaderBlockKind.QUOTE -> appendLine(b.text.lineSequence().joinToString("\n") { "> $it" })
                ReaderBlockKind.CODE -> appendLine("```\n${b.text}\n```")
                ReaderBlockKind.IMAGE -> appendLine("[图片${if (b.alt.isNotBlank()) "：${b.alt}" else ""}]")
                ReaderBlockKind.LIST_ITEM -> appendLine("- ${b.text}")
            }
            appendLine()
        }
    }.trim().ifBlank { doc.title }

    /* ---------------- 基础取值（防御式） ---------------- */

    private fun str(obj: JsonObject, key: String): String =
        runCatching { obj[key]?.jsonPrimitive?.content ?: "" }.getOrDefault("")

    private fun int(obj: JsonObject, key: String): Int =
        runCatching { obj[key]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 }.getOrDefault(0)

    private const val MAX_BLOCKS = 400
}
