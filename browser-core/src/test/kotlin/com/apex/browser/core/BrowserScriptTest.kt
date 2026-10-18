package com.apex.browser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 BrowserScript 的「意图」：它生成的 JS 字符串决定了 WebView 抓取的质量与定位稳定性。
 * 这些是纯字符串工厂（无 Android 依赖），在 JVM 即可验证。
 * 意图层失败（而非实现层）即视为 bug —— 例如有人把语义哈希 ref 改回顺序 ref、
 * 弄错策略选择器、或在 ref 插值处留下注入漏洞，都应被本测试捕获。
 */
class BrowserScriptTest {

    /**
     * 从 snapshotJs 生成的脚本中提取实际的选择器条目列表。
     *
     * 修复说明：此前用 `js.contains("a,")` 做子串匹配，会被 `textarea,` 的
     * 子串 `a,` 误命中（假阳性），“不应含裸 <a> 选择器”的断言因此永远失败；
     * 反过来 INTERACTIVE_ONLY 的断言即使删掉真正的 `a` 选择器也会因
     * `textarea,` 而侥幸通过（假阴性）。解析出真实条目才能验证意图。
     */
    private fun selectorEntries(js: String): List<String> {
        val m = Regex("var interactiveSel = '([^']*)'").find(js)
        return m?.groupValues?.get(1)?.split(',')?.map { it.trim() } ?: emptyList()
    }

    // ---- snapshotJs：三策略选择器正确性 ----

    @Test
    fun `INTERACTIVE_ONLY 策略覆盖核心可交互控件选择器`() {
        val entries = selectorEntries(BrowserScript.snapshotJs(DomParser.SnapshotStrategy.INTERACTIVE_ONLY))
        // 意图：默认快照必须能抓到链接、按钮、表单控件与常见 ARIA 角色
        for (sel in listOf("a", "button", "input", "select", "textarea",
                           "[role=button]", "[role=link]", "[role=tab]", "[role=option]")) {
            assertTrue("INTERACTIVE_ONLY 应含选择器 $sel，实际：$entries", entries.contains(sel))
        }
    }

    @Test
    fun `FORM_FIELDS 策略收窄到表单域而非整页链接`() {
        val entries = selectorEntries(BrowserScript.snapshotJs(DomParser.SnapshotStrategy.FORM_FIELDS))
        // 意图：填表场景只保留 input/select/textarea 及表单类 ARIA 角色
        for (sel in listOf("input", "select", "textarea", "[role=checkbox]")) {
            assertTrue("FORM_FIELDS 应含选择器 $sel，实际：$entries", entries.contains(sel))
        }
        // 不应把整页导航链接/按钮作为主要目标（裸 a / button 不在 FORM_FIELDS 选择器内）
        assertFalse("FORM_FIELDS 不应含裸 <a> 选择器，实际：$entries", entries.contains("a"))
        assertFalse("FORM_FIELDS 不应含裸 <button> 选择器，实际：$entries", entries.contains("button"))
    }

    @Test
    fun `CONTENT_SUMMARY 策略保留标题正文链接而非交互控件`() {
        val entries = selectorEntries(BrowserScript.snapshotJs(DomParser.SnapshotStrategy.CONTENT_SUMMARY))
        // 意图：阅读/抽取场景保留 h1~h4、p、li、链接
        for (sel in listOf("h1", "h2", "h3", "h4", "p", "li", "a[href]")) {
            assertTrue("CONTENT_SUMMARY 应含选择器 $sel，实际：$entries", entries.contains(sel))
        }
        assertFalse("CONTENT_SUMMARY 不应含裸 button 选择器，实际：$entries", entries.contains("button"))
        assertFalse("CONTENT_SUMMARY 不应含裸 input 选择器，实际：$entries", entries.contains("input"))
    }

    // ---- 语义哈希 ref：抗 SPA 局部刷新错位的核心 ----

    @Test
    fun `snapshotJs 注入 data-apex-hash 语义哈希作为定位主键`() {
        val js = BrowserScript.snapshotJs()
        // 意图：定位主键必须是语义哈希（data-apex-hash），而非顺序 ref)
        assertTrue("必须写入 data-apex-hash 属性", js.contains("setAttribute('data-apex-hash'"))
        assertTrue("必须包含哈希函数", js.contains("function hash"))
        assertTrue("哈希结果必须以 r_ 前缀稳定可读", js.contains("'r_' +"))
    }

    @Test
    fun `snapshotJs 含元素硬上限保护 token 预算`() {
        val js = BrowserScript.snapshotJs()
        // 意图：超过 SNAPSHOT_MAX_ELEMENTS 必须截断，防止大页撑爆 IPC/Token
        assertTrue(js.contains("var MAX = ${BrowserScript.SNAPSHOT_MAX_ELEMENTS}"))
        assertTrue(js.contains("if (out.length >= MAX) break"))
    }

    @Test
    fun `snapshotJs 默认策略为 INTERACTIVE_ONLY`() {
        // 意图：不传策略时回退到最通用的交互元素快照，行为稳定
        assertEquals(
            BrowserScript.snapshotJs(DomParser.SnapshotStrategy.INTERACTIVE_ONLY),
            BrowserScript.snapshotJs()
        )
    }

    // ---- 物理定位：rectByRefJs 用语义哈希 ref 而非顺序序号 ----

    @Test
    fun `rectByRefJs 按 data-apex-hash 属性值精确匹配定位`() {
        val ref = "r_3k9f"
        val js = BrowserScript.rectByRefJs(ref)
        // 意图：必须按语义哈希 ref 精确定位，才能抗 SPA 局部刷新错位
        assertTrue(js, js.contains("getAttribute('data-apex-hash') === __apexRef"))
        assertTrue("ref 应进入比较用的 JS 字符串字面量", js.contains("var __apexRef = '$ref';"))
    }

    @Test
    fun `ref 定位不经 CSS 选择器解析`() {
        // 意图：若把 ref 拼进 `[data-apex-hash=<ref>]`，CSS 解析器会二次解析 ref ——
        // 含引号/空格/`]` 的 ref 会让 querySelector 抛 SyntaxError，在无 try/catch 的
        // rectByRefJs 路径上冒泡成 ElementNotFoundException 并打开熔断器。
        // 因此定位必须走「取全部带标记元素 + JS 严格比较」。
        val js = BrowserScript.rectByRefJs("r_3k9f")
        assertFalse("不得把 ref 拼进 CSS 属性选择器", js.contains("[data-apex-hash=' +"))
        assertTrue("应查询全部带标记元素", js.contains("querySelectorAll('[data-apex-hash]')"))
    }

    @Test
    fun `注入型 ref 无法逃出字面量`() {
        // 意图：ref 来自 Agent 参数，而 Agent 上下文里可能混有网页正文（间接提示注入），
        // 因此 ref 必须在进入 JS 前被转义。旧实现 "'$this'" 零转义，此载荷可闭合字面量。
        val payload = "x']);alert(1);//"
        val js = BrowserScript.rectByRefJs(payload)
        assertTrue("闭合引号必须被转义: $js", js.contains("\\'"))
        assertFalse("不得出现未转义的闭合引号序列", js.contains("'));alert"))
    }

    // ---- 下拉选择：byText / byValue 两种匹配语义 ----

    @Test
    fun `selectJs byValue 按 option 的 value 匹配`() {
        val js = BrowserScript.selectJs("r_abc", "cn", byText = false)
        assertTrue("按 value 匹配", js.contains("opt.value"))
        assertTrue(js.contains("var __apexRef = 'r_abc';"))
        assertFalse("不应按 text 匹配", js.contains("opt.text"))
    }

    @Test
    fun `selectJs byText 按 option 的 text 匹配`() {
        val js = BrowserScript.selectJs("r_abc", "中国", byText = true)
        assertTrue("按 text 匹配", js.contains("opt.text"))
    }

    @Test
    fun `selectJs 的 option 值经转义注入`() {
        // 意图：value 是用户/Agent 提供的自由文本（如人名、地址），
        // 含单引号时不得闭合字面量。
        val js = BrowserScript.selectJs("r_abc", "O'Brien", byText = true)
        assertTrue("option 值必须转义: $js", js.contains("'O\\'Brien'"))
    }

    // ---- 网络监控：注入后拦截 fetch / xhr 并写入日志 ----

    @Test
    fun `NETWORK_MONITOR_JS 拦截 fetch 与 XMLHttpRequest 并写入日志`() {
        val js = BrowserScript.NETWORK_MONITOR_JS
        // 意图：window.__apexNetLog 是 browser_network_log 的数据源，必须被填充
        assertTrue(js.contains("window.__apexNetLog"))
        assertTrue(js.contains("window.fetch = function"))
        assertTrue(js.contains("XMLHttpRequest.prototype.open"))
        assertTrue(js.contains("XMLHttpRequest.prototype.send"))
        // 防止重复注入
        assertTrue(js.contains("if (window.__apexNetHooked) return"))
    }

    // ---- 点击后探针：返回 url/title/交互数供 Agent 判定导航是否成功 ----

    @Test
    fun `POST_ACTION_PROBE_JS 返回 url title 与交互元素数`() {
        val js = BrowserScript.POST_ACTION_PROBE_JS
        // 意图：点击验证必须能感知 URL / 标题变化与页面结构变化
        assertTrue(js.contains("url: location.href"))
        assertTrue(js.contains("title: document.title"))
        assertTrue(js.contains("interactiveCount:"))
    }

    // ---- 等待选择器：wait_for 参数支撑 ----

    @Test
    fun `selectorPresentJs 把选择器作为 JS 字符串安全嵌入`() {
        val sel = "a.login"
        val js = BrowserScript.selectorPresentJs(sel)
        // 意图：选择器被当作字面量查询
        assertTrue(js.contains("document.querySelector('$sel')"))
    }

    @Test
    fun `selectorPresentJs 必须是同步返回而非 Promise`() {
        // 意图：evaluateJavascript 的回调不等待 Promise —— 旧 waitForSelectorJs 返回
        // new Promise，导致 browser_navigate 的 wait_for 永远判定为「未找到」。
        val js = BrowserScript.selectorPresentJs(".results")
        assertTrue("不得含 Promise", !js.contains("Promise"))
        assertTrue("应同步返回布尔", js.contains("return !!"))
    }

    @Test
    fun `selectorPresentJs 转义含引号的选择器`() {
        // 真实场景：input[placeholder='搜索'] 这类带引号属性值的选择器非常常见，
        // 旧实现零转义会把它们变成 JS 语法错误，导致 navigate 永远等不到元素。
        val js = BrowserScript.selectorPresentJs("input[placeholder='搜索']")
        assertTrue("选择器中的单引号必须转义: $js", js.contains("\\'搜索\\'"))
    }

    // ---- 文本输入：input/change 事件与多行文本 ----

    @Test
    fun `inputTextJs 聚焦赋值并派发 input 与 change 事件`() {
        val js = BrowserScript.inputTextJs("r_in", "hello")
        assertTrue("必须 focus（受控组件依赖）", js.contains("el.focus()"))
        assertTrue("必须赋值", js.contains("el.value = 'hello'"))
        assertTrue("必须派发 input", js.contains("new Event('input'"))
        assertTrue("必须派发 change", js.contains("new Event('change'"))
    }

    @Test
    fun `inputTextJs 把多行文本的换行转义而非裸写入`() {
        // 意图：往 <textarea> 填多行是常规操作，裸换行会让整段 JS 变成语法错误，
        // 输入静默失效（既没有报错也没有输入结果）。
        val js = BrowserScript.inputTextJs("r_in", "第一行\n第二行")
        assertTrue("换行必须转义: $js", js.contains("\\n"))
        assertFalse("不得含裸换行", js.contains("第一行\n第二行"))
    }

    @Test
    fun `inputTextJs 的 ref 走与 BrowserScript 其余函数一致的转义`() {
        // 意图：旧实现在引擎内内联 JS 并自写了一套只处理单引号的转义，且把 ref 按
        // 双引号转义却嵌在单引号选择器里 —— 转义与定界符错配。
        val js = BrowserScript.inputTextJs("r_o'brien", "x")
        assertTrue("ref 必须经统一编码: $js", js.contains("\\'"))
        assertTrue("定位方式与其余函数一致: $js", js.contains("var __apexRef = "))
    }

    // ---- 可见文本包含 ----

    @Test
    fun `textContainsJs 检查 body 可见文本且对 innerText 缺失有回退`() {
        val js = BrowserScript.textContainsJs("已登录")
        assertTrue("应检查 body 文本", js.contains("document.body"))
        assertTrue("应做包含判断", js.contains("indexOf"))
        assertTrue("innerText 缺失时应回退 textContent", js.contains("textContent"))
    }

    @Test
    fun `textContainsJs 转义多行被等待文本`() {
        // 真实场景：等待「验证码错误」这类可能含换行的提示文案
        val js = BrowserScript.textContainsJs("line1\nline2")
        assertTrue("换行必须转义: $js", js.contains("\\n"))
    }

    // ---- 滚动与网络日志 ----

    @Test
    fun `scrollByJs 注入受限像素并同步返回`() {
        val js = BrowserScript.scrollByJs(400)
        assertTrue(js.contains("window.scrollBy(0, 400)"))
        assertTrue("应同步返回 true", js.contains("return true"))
    }

    @Test
    fun `scrollByJs 对超大位移做上限收敛`() {
        // 意图：Agent 可能传入极大 delta（如 Int.MAX_VALUE），会让 WebView 长时间
        // 滚动阻塞主线程。
        val js = BrowserScript.scrollByJs(Int.MAX_VALUE)
        assertTrue("应被收敛到上限: $js", js.contains(BrowserScript.MAX_SCROLL_PX.toString()))
        assertFalse("不得原样写入极大值", js.contains(Int.MAX_VALUE.toString()))
    }

    @Test
    fun `networkLogJs 截取尾部 limit 条且下界受保护`() {
        assertTrue(BrowserScript.networkLogJs(50).contains("slice(-50)"))
        // limit=0 时 slice(-0) == slice(0) 会返回整个数组，必须收敛到 1
        assertTrue(BrowserScript.networkLogJs(0).contains("slice(-1)"))
    }

    // ---- 高亮：调试可视化 ----

    @Test
    fun `highlightJs 对目标 ref 注入 outline`() {
        val js = BrowserScript.highlightJs("r_z12", "#ff0000")
        assertTrue("应按 data-apex-hash 精确定位: $js", js.contains("getAttribute('data-apex-hash') === __apexRef"))
        assertTrue("ref 应进入 JS 字符串字面量: $js", js.contains("var __apexRef = 'r_z12';"))
        assertTrue("应逐个高亮全部匹配元素: $js", js.contains("__apexHits[i].style.outline="))
        assertTrue("应注入 outline 样式: $js", js.contains("2px solid "))
        assertTrue(js.contains("#ff0000"))
    }

    @Test
    fun `highlightJs 的 ref 与 color 均经转义`() {
        // 意图：旧实现 "[data-apex-hash=\"$ref\"]" 与 "outline='...$color'" 双双原样插值，
        // 两种定界符都可被闭合。现 ref 走 refLookupJs、color 走 JsLiteral.string。
        val js = BrowserScript.highlightJs("x'];alert(1);//", "red'; alert(1); //")
        assertTrue("ref 中的单引号必须转义: $js", js.contains("\\'"))
        assertTrue("color 中的单引号必须转义: $js", js.contains("red\\'; alert(1); //"))
        assertFalse("不得出现未转义的闭合引号序列", js.contains("'));alert"))
    }

    // ---- 可见性剪枝：不得误杀 position:fixed 元素 ----

    @Test
    fun `snapshotJs 不以 offsetParent 作为可见性判据`() {
        // 意图：position:fixed 的可见元素其 offsetParent 恒为 null，用它判可见会
        // 误杀吸顶导航 / 悬浮按钮 / 弹窗控件 —— 恰是现代站点最需要点击的元素。
        val js = BrowserScript.snapshotJs()
        assertFalse("不得依赖 offsetParent 判可见", js.contains("offsetParent"))
        assertTrue("应基于 rect 与样式联合判定", js.contains("rect.width > 0"))
    }

    // ---- A11y 降级源：不得因 innerText 缺失而整体失败 ----

    @Test
    fun `A11Y_FALLBACK_JS 用单反斜杠空白正则`() {
        // 意图：Kotlin 原始字符串里的 /\\\\s+/ 会产出 JS 的 /\\s+/ —— 匹配「字面反斜杠 + s」，
        // 而非空白字符，导致降级源的文本从不折叠空白。
        val js = BrowserScript.A11Y_FALLBACK_JS
        assertFalse("不得含双反斜杠正则 /\\\\s+/", js.contains("\\\\s+"))
        assertTrue("应含正确的空白正则 /\\s+/", js.contains("/\\s+/g"))
    }

    @Test
    fun `A11Y_FALLBACK_JS 对 innerText 缺失有 textContent 回退`() {
        // 意图：querySelectorAll('*') 会命中 SVG / void 元素，其 innerText 为 undefined。
        // 旧实现对 innerText 直接调 .trim()，任一此类元素即让整个降级快照抛错返回空，
        // 而这正是「主快照失败后的最后一道降级」——静默失效最难排查。
        val js = BrowserScript.A11Y_FALLBACK_JS
        assertTrue("应有 textContent 回退", js.contains("textContent"))
        assertFalse("不得对 innerText 直接调 .trim()", js.contains("innerText.trim()"))
    }

    @Test
    fun `A11Y_FALLBACK_JS 同样写入语义哈希并保持同步`() {
        val js = BrowserScript.A11Y_FALLBACK_JS
        assertTrue("应写入 data-apex-hash", js.contains("setAttribute('data-apex-hash'"))
        assertTrue("不得含 Promise", !js.contains("Promise"))
    }

    // ═══ v1.1.0 高级能力脚本（自 main 移植，断言适配 JsLiteral/refLookupJs） ═══

    @Test
    fun `setNativeValueJs 多行文本转义为 n 字面量`() {
        val js = BrowserScript.setNativeValueJs("r_1", "第一行\n第二行", append = false)
        // 意图：换行必须变成 \n 转义序列，而不是字面换行（撕裂 JS 字符串的旧病）
        assertFalse("生成的 JS 内不应有字面换行出现在字符串字面量中", js.contains("第一行\n第二行"))
        assertTrue(js.contains("\\n"))
    }

    @Test
    fun `setNativeValueJs 走原型链原生 setter 并派发事件`() {
        val js = BrowserScript.setNativeValueJs("r_1", "hello", append = false)
        // 意图：React/Vue 受控组件兼容——native setter + input/change 事件
        assertTrue(js.contains("getOwnPropertyDescriptor"))
        assertTrue(js.contains("desc.set.call"))
        assertTrue(js.contains("new Event('input'"))
        assertTrue(js.contains("new Event('change'"))
        assertTrue(js.contains("isContentEditable"))
    }

    @Test
    fun `setNativeValueJs append 模式拼接现值`() {
        val appendJs = BrowserScript.setNativeValueJs("r_1", "x", append = true)
        val replaceJs = BrowserScript.setNativeValueJs("r_1", "x", append = false)
        assertTrue("append 模式应拼接现值", appendJs.contains("(cur || '') +"))
        assertFalse("replace 模式不应拼接", replaceJs.contains("(cur || '') +"))
    }

    @Test
    fun `scrollIntoViewAndRectJs 先滚动到中央再回读矩形`() {
        val js = BrowserScript.scrollIntoViewAndRectJs("r_1")
        assertTrue(js.contains("scrollIntoView"))
        assertTrue(js.contains("block:'center'"))
        assertTrue(js.contains("getBoundingClientRect"))
        assertTrue(js.contains("window.innerWidth"))
    }

    @Test
    fun `elementAtPointJs 做命中测试并判定目标归属`() {
        val js = BrowserScript.elementAtPointJs(120.5f, 300f, "r_1")
        assertTrue(js.contains("document.elementFromPoint(120.5, 300"))
        assertTrue(js.contains("isTargetOrChild"))
        // rebase 适配：命中元素改名 __apexHit（refLookupJs 占用 el 名），归属判定等价
        assertTrue(js.contains("el.contains(__apexHit)"))
    }

    @Test
    fun `locateByFuzzyJs 命中后重打原 ref`() {
        val js = BrowserScript.locateByFuzzyJs("button", "提交订单", "r_abc")
        assertTrue(js.contains("indexOf(want)"))
        assertTrue(js.contains("bestLen")) // 最短文本 = 最精确匹配
        assertTrue(js.contains("setAttribute('data-apex-hash'"))
    }

    @Test
    fun `pressKeyJs 的 Enter 携带表单提交语义`() {
        val js = BrowserScript.pressKeyJs("enter")
        assertTrue(js.contains("keydown"))
        assertTrue(js.contains("keyup"))
        assertTrue(js.contains("requestSubmit"))
    }

    @Test
    fun `pressKeyJs 未知键按单字符派发`() {
        val js = BrowserScript.pressKeyJs("a")
        assertTrue(js.contains("keydown"))
        // 意图：键值如实下发为单字符（Enter 分支是模板常驻代码，由运行时键值门控）
        assertTrue("键值应按单字符下发", js.contains("var key = 'a'"))
        assertFalse("单字符键的键值不应是 Enter", js.contains("var key = 'Enter'"))
    }

    @Test
    fun `hoverJs 派发完整悬停事件序列`() {
        val js = BrowserScript.hoverJs("r_1")
        assertTrue(js.contains("mouseover"))
        assertTrue(js.contains("mouseenter"))
        assertTrue(js.contains("mousemove"))
    }

    @Test
    fun `extractContentJs 四模式意图齐全`() {
        for (mode in listOf("article", "tables", "links", "meta")) {
            val js = BrowserScript.extractContentJs(mode)
            assertTrue("模式 $mode 应如实下发", js.contains("var mode = '$mode'"))
        }
        // article 模式的正文抽取骨架
        val art = BrowserScript.extractContentJs("article")
        assertTrue(art.contains("querySelector('article')"))
        assertTrue(art.contains("wordCount"))
        // tables 模式行列封顶
        val tabs = BrowserScript.extractContentJs("tables")
        assertTrue(tabs.contains("rows.length<50"))
        assertTrue(tabs.contains("cells.length<30"))
        // links 模式去重
        val links = BrowserScript.extractContentJs("links")
        assertTrue(links.contains("seen[h]"))
        // meta 模式 og 标签
        val meta = BrowserScript.extractContentJs("meta")
        assertTrue(meta.contains("og:title"))
        assertTrue(meta.contains("canonical"))
    }

    // -- v1.1.0 防线回归：所有 ref 定位入口共享统一前置片段 --

    @Test
    fun `八个 ref 定位入口全部改用属性值比较`() {
        // 意图：每个靠 ref 定位的注入点都必须同步，否则修了其中几处仍留一个可注入面。
        // 函数名由脚本从源码映射得出，与 8 个注入点一一对应（v1.1.0 七处 + inputTextJs）。
        val jsSites = listOf(
            "rectByRefJs" to BrowserScript.rectByRefJs("r_3k9f"),
            "selectJs" to BrowserScript.selectJs("r_9z", "cn", byText = false),
            "highlightJs" to BrowserScript.highlightJs("r_hl"),
            "inputTextJs" to BrowserScript.inputTextJs("r_it", "hi"),
            "scrollIntoViewAndRectJs" to BrowserScript.scrollIntoViewAndRectJs("r_sv"),
            "elementAtPointJs" to BrowserScript.elementAtPointJs(100f, 200f, "r_ep"),
            "setNativeValueJs" to BrowserScript.setNativeValueJs("r_nv", "hi", false),
            "hoverJs" to BrowserScript.hoverJs("r_hv"),
        )
        assertEquals("ref 定位入口数量", 8, jsSites.size)
        for ((name, js) in jsSites) {
            assertFalse(
                name + " 仍把 ref 拼进 CSS 属性选择器",
                js.contains("[data-apex-hash=" + "'" + " +"),
            )
            assertFalse(
                name + " 仍保留双重引号形态",
                js.contains("'" + "[data-apex-hash="),
            )
            assertTrue(
                name + " 应通过 querySelectorAll + 属性值比较定位",
                js.contains("getAttribute(" + "'" + "data-apex-hash" + "'" + ") ==="),
            )
        }
    }

    @Test
    fun `A11Y_FALLBACK_JS 的 rect 与 snapshotJs 统一为文档坐标`() {
        // 意图：两条快照路径若坐标系不一致（一个视口、一个文档），坐标兜底在 A11y
        // 路径上会指向错误位置。两者都必须加上 scroll 偏移。
        for ((name, js) in listOf(
            "snapshotJs" to BrowserScript.snapshotJs(),
            "A11Y_FALLBACK_JS" to BrowserScript.A11Y_FALLBACK_JS,
        )) {
            assertTrue(
                "$name 的 x 应为文档坐标（加 scrollX）：$js",
                js.contains("rect.left + window.scrollX"),
            )
            assertTrue(
                "$name 的 y 应为文档坐标（加 scrollY）：$js",
                js.contains("rect.top + window.scrollY"),
            )
        }
    }

    @Test
    fun `定位片段声明统一的 __apexHits 与 el`() {
        // 意图：多个注入点共用同一前置片段（refLookupJs），变量名不能各处自定义
        //（重名会静默抬高一个入口）。rebase 适配：__apexFirst 并入 el。
        val js = BrowserScript.rectByRefJs("r_3k9f")
        assertTrue(js.contains("var __apexHits = [];"))
        assertTrue(js.contains("var el = __apexHits.length ? __apexHits[0] : null;"))
        // 循环变量必须带前缀，避免与调用方脚本里的 i / el 碰撞
        assertTrue(js.contains("__apexI"))
    }

    @Test
    fun `ref 不得以表达式文本泄漏到生成的 JS 里`() {
        // 意图：曾经写过 refHitsJs("ref.toJsonString()") —— Kotlin 传的是表达式的
        // *文本*，不是它的值。生成的 JS 里因此出现 `=== ref.toJsonString()`，
        // 里面的 ref 是未定义变量 -> ReferenceError，ref 定位又成了完全不可用。
        // 本测试把这一类错误固定为不可能（rebase 后同样防 JsLiteral.string 文本泄漏）。
        val js = BrowserScript.rectByRefJs("r_3k9f")
        assertFalse(
            "生成的 JS 里不得出现 Kotlin 表达式文本：" + js,
            js.contains("toJsonString()"),
        )
        assertFalse(
            "生成的 JS 里不得出现 Kotlin 表达式文本：" + js,
            js.contains("JsLiteral.string"),
        )
        assertFalse(
            "生成的 JS 里不得出现未定义的 Kotlin 参数名：" + js,
            js.contains("=== ref"),
        )
        // ref 必须以已转义的 JS 字符串字面量出现，而不是代码块
        assertTrue(js.contains("var __apexRef = 'r_3k9f';"))
    }

    // ---- 截断诚实性 + 真实 depth：信封契约 ----

    @Test
    fun `snapshotJs 回传 total 与 truncated 而非静默截断`() {
        // 意图：脚本按 MAX 硬上限 break，若不回传总量，模型会把这一批当成页面全部。
        val js = BrowserScript.snapshotJs()
        assertTrue("应先记录匹配总数", js.contains("var total = all.length;"))
        assertTrue("应回传 total", js.contains("total: total"))
        assertTrue("应回传 truncated", js.contains("truncated: total > out.length"))
        assertTrue("应回传信封版本号", js.contains("v: 1"))
        assertTrue("元素应放在 elements 键下", js.contains("elements: out"))
    }

    @Test
    fun `A11Y_FALLBACK_JS 同样回传信封`() {
        val js = BrowserScript.A11Y_FALLBACK_JS
        assertTrue(js.contains("truncated: total > out.length"))
        assertTrue(js.contains("elements: out"))
    }

    @Test
    fun `两条快照路径都计算真实 DOM 深度而非恒 0`() {
        // 意图：摘要超预算时按「可交互 > 浅层 > 有标签」决定谁先进预算，
        // 该优先级依赖 depth；旧实现恒写 0，使文档承诺的优先级从未生效。
        for ((name, js) in listOf(
            "snapshotJs" to BrowserScript.snapshotJs(),
            "A11Y_FALLBACK_JS" to BrowserScript.A11Y_FALLBACK_JS,
        )) {
            // 断言 parentElement 本身而非 `el.parentElement`：JS 里的遍历变量名是
            // 局部实现细节，写死变量名会让一次无害改名变成一次假失败。
            assertTrue("$name 应遍历父链计算 depth", js.contains("parentElement"))
            assertTrue("$name 应为 depth 设上限避免深 DOM 拖慢脚本", js.contains("depth < 20"))
            assertFalse("$name 不得把 depth 写死 0", js.contains("depth: 0,"))
        }
    }

    // ═══ v1.3.0：ref 同键去重 / a11y total 口径 / locateJs 收敛 / 等待谓词脚本 ═══

    @Test
    fun `snapshotJs 对同 semanticKey 的 ref 追加井号序号去重`() {
        val js = BrowserScript.snapshotJs()
        // 意图：列表页大量「角色+文案+位置」完全相同的元素（同一批商品卡/同一组
        // 工具按钮）会算出同一个 hash，data-apex-hash 撞键后 ref 定位永远命中
        // 第一个——其余同名元素永远不可操作。第 2+ 次出现必须追加 '#n' 保唯一。
        assertTrue("应有 seen 计数表", js.contains("var seen = {};"))
        assertTrue("应查表取出现次数", js.contains("seen[semanticKey]"))
        assertTrue("第 2+ 次出现应追加 #n 后缀", js.contains("'#' + (n + 1)"))
    }

    @Test
    fun `A11Y_FALLBACK_JS 同样做同键去重`() {
        val js = BrowserScript.A11Y_FALLBACK_JS
        assertTrue("应有 seen 计数表", js.contains("var seen = {};"))
        assertTrue("第 2+ 次出现应追加 #n 后缀", js.contains("'#' + (n + 1)"))
    }

    @Test
    fun `A11Y total 为语义节点计数而非全节点数`() {
        val js = BrowserScript.A11Y_FALLBACK_JS
        // 意图：旧口径 total = querySelectorAll('*').length，把纯布局 div/script/head
        // 全部计入（动辄上千），truncated 恒为真——模型被误导「页面还有大量可操作
        // 元素没抓到」。必须是「有 name/role 的语义节点」循环内计数。
        assertFalse("不得以全节点数为 total", js.contains("var total = all.length;"))
        assertTrue("应为循环内递增计数器", js.contains("total++"))
        assertTrue("截断标记仍与 out 长度对比", js.contains("truncated: total > out.length"))
    }

    @Test
    fun `locateJs 对抗语料 - U2028 行分隔符必须转义`() {
        // 意图：U+2028 在 JSON 合法但 JS 字符串字面量非法（ES2019 前的著名陷阱），
        // 裸拼会让整段 evaluateJavascript 直接语法错误。旧引擎内联版完全没有转义。
        val js = BrowserScript.locateJs("a\u2028b", "*", 10)
        assertFalse("不得出现裸 U+2028", js.contains("\u2028"))
        assertTrue("应转义为 \\u2028 字面量", js.contains("\\u2028"))
    }

    @Test
    fun `locateJs 对抗语料 - 单引号闭合注入必须转义`() {
        val js = BrowserScript.locateJs("x']);alert(1);//", "button", 10)
        assertTrue("单引号必须转义", js.contains("\\'"))
        assertFalse("不得出现未转义闭合序列", js.contains("'));alert"))
        assertTrue("定位文本以字面量进入脚本", js.contains("var want = 'x\\']);alert(1);//';"))
    }

    @Test
    fun `locateJs tag 参数同样转义且 limit 收敛`() {
        val js = BrowserScript.locateJs("提交", "input[name='q']", 99)
        assertTrue("tag 中的引号必须转义", js.contains("input[name=\\'q\\']"))
        assertTrue("limit 收敛到 50 上限", js.contains("out.length<50"))
        // 文本取值链与 locateByFuzzyJs 统一（含 placeholder）
        assertTrue(js.contains("el.getAttribute('placeholder')"))
    }

    @Test
    fun `locateJs 命中元素打上可复用的语义哈希 ref`() {
        val js = BrowserScript.locateJs("提交订单", "button", 10)
        assertTrue("无 hash 时应现场计算并打标", js.contains("setAttribute('data-apex-hash'"))
        assertTrue("语义键含 role（与快照口径统一）", js.contains("getAttribute('role') || el.tagName.toLowerCase()"))
    }

    @Test
    fun `selectorCountJs 返回 querySelectorAll 计数且转义选择器`() {
        val js = BrowserScript.selectorCountJs("a[href^='x']")
        assertTrue(
            "选择器经 JsLiteral 编码嵌入",
            js.contains("document.querySelectorAll('a[href^=\\'x\\']').length"),
        )
        assertTrue("同步返回数值（无 Promise）", !js.contains("Promise"))
    }

    @Test
    fun `networkIdleJs 同步返回在途计数`() {
        val js = BrowserScript.networkIdleJs()
        assertTrue(js.contains("window.__apexNetPending || 0"))
        assertFalse("不得含 Promise（evaluateJavascript 不等待）", js.contains("Promise"))
    }

    @Test
    fun `NETWORK_MONITOR_JS 维护在途计数供 networkIdleJs 消费`() {
        val js = BrowserScript.NETWORK_MONITOR_JS
        assertTrue("发起请求应 +1", js.contains("window.__apexNetPending++"))
        assertTrue("settle 应递减且不落负", js.contains("Math.max(0, window.__apexNetPending - 1)"))
        assertTrue("fetch 与 xhr 链路都挂 begin", js.contains("begin();"))
    }

    @Test
    fun `STEALTH_JS 下沉到 BrowserScript 且保持隐身语义`() {
        val js = BrowserScript.STEALTH_JS
        assertTrue(js.contains("navigator, 'webdriver'"))
        assertTrue(js.contains("navigator, 'languages'"))
        assertFalse("不得含 Promise", js.contains("Promise"))
    }
}
