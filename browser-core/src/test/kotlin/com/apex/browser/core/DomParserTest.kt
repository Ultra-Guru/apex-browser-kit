package com.apex.browser.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 DomParser 的「意图」：把浏览器抓取的原始 DOM 转成 Agent 可操作的快照，
 * 并在 token 预算内压缩摘要。意图层失败（而非实现层）即视为 bug。
 */
class DomParserTest {

    private fun rawJson(elements: List<RawDomElement>): String =
        Json.encodeToString(elements)

    /**
     * 新版注入脚本的回传信封形态 {v,total,truncated,elements}。
     *
     * 直接拼字符串而非用 buildJsonObject：只需 `Json.encodeToString` 这一个已在
     * 本文件验证可编译的 API。（`Json.encodeToJsonElement` 的 reified 形态是
     * `kotlinx.serialization.json` 包内的**扩展函数**，漏 import 会静默解析到
     * 双参重载 `encodeToJsonElement(serializer, value)` 而编译失败。）
     * total 是 Int、truncated 是 Boolean，字面量插值安全。
     */
    private fun envelopeJson(
        elements: List<RawDomElement>,
        total: Int,
        truncated: Boolean,
    ): String =
        """{"v":1,"total":$total,"truncated":$truncated,"elements":${Json.encodeToString(elements)}}"""

    private fun el(
        tag: String,
        text: String? = null,
        attrs: Map<String, String> = emptyMap(),
        rect: Rect = Rect(0, 0, 10, 10),
        visible: Boolean = true,
        interactive: Boolean = true,
        depth: Int = 0,
        childCount: Int = 0,
    ) = RawDomElement(tag, text, attrs, rect, visible, interactive, depth, childCount)

    private fun hashed(hash: String) = mapOf("data-apex-hash" to hash)

    @Test
    fun `为可交互元素分配从 1 开始的连续 bid`() {
        val raw = rawJson(
            listOf(
                RawDomElement("A", "首页", mapOf("href" to "/"), Rect(0, 0, 10, 10), true, true, 0, 0),
                RawDomElement("BUTTON", "提交", emptyMap(), Rect(0, 0, 10, 10), true, true, 0, 0),
            )
        )
        val snap = DomParser.parse(raw, "https://x.com", "首页", 0, 1000, 800)
        assertEquals(2, snap.interactiveCount)
        assertEquals(1, snap.interactiveElements[0].bid)
        assertEquals(2, snap.interactiveElements[1].bid)
    }

    @Test
    fun `ref 来自语义哈希 data-apex-hash 而非顺序序号（抗 SPA 局部刷新错位）`() {
        // 意图：SPA 列表插入新数据会导致顺序 bid 偏移，但语义哈希 ref 由元素自身特征决定，保持稳定。
        val raw = rawJson(
            listOf(
                RawDomElement(
                    "BUTTON", "加入购物车",
                    mapOf("data-apex-hash" to "r_3k9f", "aria-label" to "加入购物车"),
                    Rect(0, 0, 10, 10), true, true, 0, 0
                ),
                RawDomElement(
                    "A", "下一页",
                    mapOf("data-apex-hash" to "r_8ab2", "href" to "/next"),
                    Rect(0, 0, 10, 10), true, true, 0, 0
                ),
            )
        )
        val snap = DomParser.parse(raw, "u", "t", 0, 100, 100)
        // bid 仍是展示序号，但定位主键 ref 必须等于注入的语义哈希
        assertEquals("r_3k9f", snap.interactiveElements[0].ref)
        assertEquals("r_8ab2", snap.interactiveElements[1].ref)
        // 即使插入新元素改变了顺序，ref 不随顺序变化 —— 这正是与顺序 bid 的本质区别
        assertEquals(1, snap.interactiveElements[0].bid)
    }

    @Test
    fun `过滤不可见与非交互元素`() {
        val raw = rawJson(
            listOf(
                RawDomElement("DIV", "花边新闻", emptyMap(), Rect(0, 0, 10, 10), false, false, 0, 0),
                RawDomElement("SPAN", "纯文本", emptyMap(), Rect(0, 0, 10, 10), true, false, 0, 0),
                RawDomElement("A", "链接", mapOf("href" to "/a"), Rect(0, 0, 10, 10), true, true, 0, 0),
            )
        )
        val snap = DomParser.parse(raw, "u", "t", 0, 100, 100)
        // 只有 <a> 被保留
        assertEquals(1, snap.interactiveCount)
        assertEquals("a", snap.interactiveElements[0].tag)
    }

    @Test
    fun `输入框按 type 生成语义 label`() {
        val raw = rawJson(
            listOf(
                RawDomElement(
                    "INPUT", "", mapOf("type" to "search", "placeholder" to "搜索"),
                    Rect(0, 0, 10, 10), true, true, 0, 0
                ),
            )
        )
        val snap = DomParser.parse(raw, "u", "t", 0, 100, 100)
        assertTrue(snap.interactiveElements[0].label.contains("输入框"))
        assertTrue(snap.interactiveElements[0].label.contains("搜索"))
    }

    @Test
    fun `超出 token 预算时折叠低优先级元素且保留计数`() {
        // 构造 50 个可交互元素，预算设得很小，应触发折叠
        val many = (1..50).map {
            RawDomElement("A", "链接$it", mapOf("href" to "/$it"), Rect(0, 0, 10, 10), true, true, 5, 0)
        }
        val snap = DomParser.parse(rawJson(many), "u", "t", 0, 100, 100, tokenBudget = 200)
        // 摘要中应出现折叠提示，且 interactiveElements 仍完整保留（工具可操作）
        assertTrue(snap.domSummary.contains("折叠"))
        assertEquals(50, snap.interactiveCount)
    }

    @Test
    fun `FORM_FIELDS 策略仅保留表单类元素`() {
        // 意图：填表场景下不应把整页按钮/链接灌进 prompt，只保留 input/select/textarea 等。
        val raw = rawJson(
            listOf(
                RawDomElement("INPUT", "", mapOf("type" to "text", "placeholder" to "姓名"), Rect(0, 0, 10, 10), true, true, 0, 0),
                RawDomElement("SELECT", "", mapOf("name" to "city"), Rect(0, 0, 10, 10), true, true, 0, 0),
                RawDomElement("A", "首页", mapOf("href" to "/"), Rect(0, 0, 10, 10), true, true, 0, 0),
                RawDomElement("BUTTON", "提交", emptyMap(), Rect(0, 0, 10, 10), true, true, 0, 0),
            )
        )
        val snap = DomParser.parse(
            raw, "u", "t", 0, 100, 100,
            strategy = DomParser.SnapshotStrategy.FORM_FIELDS
        )
        assertEquals(2, snap.interactiveCount)
        assertTrue(snap.interactiveElements.all { it.tag in setOf("input", "select") })
    }

    @Test
    fun `折叠提示不得指引不存在的工具`() {
        // 意图：domSummary 是直接进 prompt 的模型可见文案。若提示里出现不存在的工具名，
        // 模型会真的去调用它并拿到「工具不存在」错误，白白浪费一轮推理。
        // 旧实现写的是 browser_dump —— 浏览器工具集里并无此工具。
        val many = (1..50).map {
            RawDomElement("A", "链接$it", mapOf("href" to "/$it"), Rect(0, 0, 10, 10), true, true, 5, 0)
        }
        val snap = DomParser.parse(rawJson(many), "u", "t", 0, 100, 100, tokenBudget = 200)
        assertTrue(snap.domSummary.contains("折叠"))
        assertFalse(
            "折叠提示不得引用不存在的 browser_dump：\n${snap.domSummary}",
            snap.domSummary.contains("browser_dump"),
        )
    }

    // ── ref 诚实性：绝不编造无法解析的 ref ────────────────────────────────

    @Test
    fun `缺失 data-apex-hash 时不得编造形似合法哈希的 ref`() {
        // 意图：ref 是 click/input/select 唯一的定位主键，全部经 data-apex-hash 反查。
        // 旧实现回退成 "r$bid" —— 形似语义哈希却永远查不到元素，于是每次操作白耗
        // 3 次重试并**打开熔断器**：一个坏元素足以锁死整段浏览器会话。
        val raw = rawJson(
            listOf(
                el("A", "首页", mapOf("href" to "/")),
                el("BUTTON", "提交"),
            )
        )
        val snap = DomParser.parse(raw, "u", "t", 0, 100, 100)
        assertEquals("不得凭空造出 ref", listOf("", ""), snap.interactiveElements.map { it.ref })
        assertTrue(
            "不得出现 r1 / r2 这类顺序伪 ref",
            snap.interactiveElements.none { it.ref.matches(Regex("r\\d+")) },
        )
    }

    @Test
    fun `摘要标明无可用 ref 的元素不可操作`() {
        // 意图：无 ref 的元素对 Agent 不可点击，必须在模型可见文案里说清，
        // 否则模型会把展示序号当 ref 回传，得到一个必然失败的调用。
        val raw = rawJson(listOf(el("A", "首页", mapOf("href" to "/"))))
        val snap = DomParser.parse(raw, "u", "t", 0, 100, 100)
        assertTrue(
            "应声明存在无可用 ref 的元素：\n${snap.domSummary}",
            snap.domSummary.contains("无可用 ref"),
        )
        assertTrue("应标注不可操作：\n${snap.domSummary}", snap.domSummary.contains("不可操作"))
    }

    @Test
    fun `有 data-apex-hash 的元素照常以 ref 渲染`() {
        val raw = rawJson(
            listOf(el("A", "首页", hashed("r_abc") + mapOf("href" to "/")))
        )
        val snap = DomParser.parse(raw, "u", "t", 0, 100, 100)
        assertEquals("r_abc", snap.interactiveElements.single().ref)
        assertTrue(snap.domSummary.contains("[r_abc]"))
        assertFalse(
            "全部元素都有 ref 时不应出现不可操作标注：\n${snap.domSummary}",
            snap.domSummary.contains("不可操作"),
        )
    }

    // ── 截断诚实性：单次抓取上限必须告知模型 ─────────────────────────────

    @Test
    fun `解析新版信封形态并如实上报截断`() {
        // 意图：注入脚本按 SNAPSHOT_MAX_ELEMENTS 硬上限截断。若不告知，模型会把
        // 「看到的 N 个」当成「页面全部」，稠密列表页据此规划动作必然踩空。
        val elements = (1..5).map { el("A", "项$it", hashed("r_$it")) }
        val snap = DomParser.parse(
            envelopeJson(elements, total = 320, truncated = true), "u", "t", 0, 100, 100,
        )
        assertTrue("PageSnapshot.truncated 应为 true", snap.truncated)
        assertEquals("应保留脚本回传的真实匹配总数", 320, snap.totalCandidateCount)
        assertEquals(5, snap.interactiveCount)
        assertTrue(
            "摘要须告知模型页面还有更多元素：\n${snap.domSummary}",
            snap.domSummary.contains("320"),
        )
    }

    @Test
    fun `未截断时不上报截断`() {
        val elements = (1..3).map { el("A", "项$it", hashed("r_$it")) }
        val snap = DomParser.parse(
            envelopeJson(elements, total = 3, truncated = false), "u", "t", 0, 100, 100,
        )
        assertFalse(snap.truncated)
        assertEquals(3, snap.totalCandidateCount)
    }

    @Test
    fun `兼容旧版裸数组形态`() {
        // 意图：parse 是公开 API，任何自行构造 raw JSON 的消费方都还在传裸数组，
        // 不能因新增信封而破坏。旧形态下截断信息未知，保守视为未截断。
        val elements = listOf(el("A", "首页", hashed("r_abc")))
        val snap = DomParser.parse(rawJson(elements), "u", "t", 0, 100, 100)
        assertEquals(1, snap.interactiveCount)
        assertEquals("r_abc", snap.interactiveElements.single().ref)
        assertFalse("旧形态无法判断截断，不应谎报", snap.truncated)
        assertEquals(1, snap.totalCandidateCount)
    }

    @Test
    fun `畸形输入既不抛错也不谎报截断`() {
        for (bad in listOf("", "null", "not-json", "[]", "{}", "[1,2,3]")) {
            val snap = DomParser.parse(bad, "u", "t", 0, 100, 100)
            assertEquals("畸形输入应得到空快照: '$bad'", 0, snap.interactiveCount)
            assertFalse("畸形输入不得谎报截断: '$bad'", snap.truncated)
        }
    }

    // ── 摘要优先级：超预算时保留可操作 / 浅层元素 ─────────────────────────

    @Test
    fun `超预算时优先保留可交互与浅层元素`() {
        // 意图：KDoc 一直声称「优先裁剪深层、低信息量元素」，但实现只是按顺序截断，
        // 而注入脚本恒把 depth 写 0 —— 被丢掉的常是页面末尾的次要控件，
        // 顶部的导航/搜索框等关键控件反而可能消失。
        val raw = rawJson(
            listOf(
                el("A", "顶部导航", hashed("r_shallow"), depth = 1),
                el("A", "深层容器里的次要链接", hashed("r_deep"), depth = 18),
                el("A", "深层另一个次要链接", hashed("r_deep2"), depth = 19),
            )
        )
        val snap = DomParser.parse(raw, "u", "t", 0, 100, 100, tokenBudget = 60)
        assertTrue(
            "浅层元素必须进预算：\n${snap.domSummary}",
            snap.domSummary.contains("r_shallow"),
        )
        assertTrue("预算确实被打满：\n${snap.domSummary}", snap.domSummary.contains("折叠"))
    }

    @Test
    fun `摘要按文档顺序渲染而非优先级顺序`() {
        // 意图：优先级只决定「谁进预算」，输出顺序必须保持页面自然阅读顺序，
        // 否则省 token 会顺带打乱模型对页面结构的理解。
        val raw = rawJson(
            listOf(
                el("A", "甲", hashed("r_a"), depth = 9),
                el("A", "乙", hashed("r_b"), depth = 1),
                el("A", "丙", hashed("r_c"), depth = 5),
            )
        )
        val snap = DomParser.parse(raw, "u", "t", 0, 100, 100, tokenBudget = 10_000)
        val idxA = snap.domSummary.indexOf("[r_a]")
        val idxB = snap.domSummary.indexOf("[r_b]")
        val idxC = snap.domSummary.indexOf("[r_c]")
        assertTrue("三者都应在预算内", idxA > 0 && idxB > 0 && idxC > 0)
        assertTrue("输出应保持 bid 顺序", idxA < idxB && idxB < idxC)
    }

    @Test
    fun `CONTENT_SUMMARY 策略保留文本与链接而非交互控件`() {
        // 意图：纯阅读/抽取场景，应优先返回有文本或链接语义的节点，剔除空按钮。
        val raw = rawJson(
            listOf(
                RawDomElement("H1", "文章标题", emptyMap(), Rect(0, 0, 10, 10), true, false, 0, 0),
                RawDomElement("P", "正文段落内容", emptyMap(), Rect(0, 0, 10, 10), true, false, 0, 0),
                RawDomElement("A", "相关链接", mapOf("href" to "/rel"), Rect(0, 0, 10, 10), true, true, 0, 0),
                RawDomElement("BUTTON", "", emptyMap(), Rect(0, 0, 10, 10), true, true, 0, 0),
            )
        )
        val snap = DomParser.parse(
            raw, "u", "t", 0, 100, 100,
            strategy = DomParser.SnapshotStrategy.CONTENT_SUMMARY
        )
        // 空 BUTTON 被剔除，保留标题/段落/链接
        assertEquals(3, snap.interactiveCount)
        assertTrue(snap.interactiveElements.none { it.tag == "button" })
    }

    // ═══ v1.3.0：PageSnapshot 新增尾参（timestampMs / dialogNotice）契约 ═══

    @Test
    fun `timestampMs 与 dialogNotice 为带默认值尾参且 parse 侧默认为零值`() {
        // 意图：两个新字段是「带默认值的尾参」——DomParser.parse 既有构造点零改动，
        // 默认 0 / null 保持旧消费方行为不变（引擎侧负责填充）。
        val snap = DomParser.parse(rawJson(listOf(el("A", "链接", hashed("r_1")))), "u", "t", 0, 100, 100)
        assertEquals(0, snap.timestampMs)
        assertEquals(null, snap.dialogNotice)
    }

    @Test
    fun `timestampMs 与 dialogNotice 经 copy 透传不丢`() {
        // 意图：引擎在 snapshot() 出口用 .copy(timestampMs=..., dialogNotice=...) 融合
        // 两个字段 —— data class copy 必须无损透传全部既有字段，否则融合即丢失。
        val snap = DomParser.parse(rawJson(listOf(el("A", "链接", hashed("r_1")))), "u", "t", 0, 100, 100)
        val fused = snap.copy(timestampMs = 1760000000000L, dialogNotice = "confirm: 确认提交？")
        assertEquals(1760000000000L, fused.timestampMs)
        assertEquals("confirm: 确认提交？", fused.dialogNotice)
        // 既有字段不受融合影响
        assertEquals("u", fused.url)
        assertEquals(1, fused.interactiveCount)
        assertEquals("r_1", fused.interactiveElements[0].ref)
    }

    @Test
    fun `timestampMs 与 dialogNotice 序列化往返保持`() {
        // 意图：PageSnapshot 是 @Serializable（宿主可能落盘/跨进程传快照），
        // 新字段必须随序列化往返保留；旧 JSON（无新字段）反序列化回落默认值。
        val snap = DomParser.parse(rawJson(listOf(el("A", "链接", hashed("r_1")))), "u", "t", 0, 100, 100)
            .copy(timestampMs = 42L, dialogNotice = "alert: hello")
        val encoded = Json.encodeToString(snap)
        val decoded = Json.decodeFromString<PageSnapshot>(encoded)
        assertEquals(42L, decoded.timestampMs)
        assertEquals("alert: hello", decoded.dialogNotice)
    }
}
