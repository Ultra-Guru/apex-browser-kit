package com.apex.browser.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/*
 * ChromeStrings 契约测试（已知取舍 #1 消解的回归锁，纯 JVM 可跑）：
 * - Zh 实现与 v1.1.0 硬编码逐字一致 —— 注入机制落地必须行为零变化；
 * - Zh / English 两实现全部 13 条文案非空（防「加成员忘翻译」漂移）；
 * - tabSwitchHint 必须包含 host（用户靠它辨认目标站点）；
 * - 接口抽象成员数锁定 13 —— 未来新增文案成员时本测试转红，
 *   逼着同步补两实现与断言，而不是只改一处悄悄漏掉另一处。
 */
class ChromeStringsTest {

    /** 全部 13 条成员的取值清单（接口新增成员时必须同步扩这里）。 */
    private fun allStrings(s: ChromeStrings): List<String> = listOf(
        s.snackTabClosed,
        s.snackUndo,
        s.snackOthersClosed,
        s.snackAllClosed,
        s.snackDesktopModeOn,
        s.snackDesktopModeOff,
        s.suggestionClipboardHint,
        s.suggestionUrlLabel,
        s.suggestionSearchLabel,
        s.jsDialogConfirmLabel,
        s.jsDialogDenyLabel,
        s.jsDialogDismissLabel,
        s.tabSwitchHint("example.com"),
    )

    @Test
    fun `中文实现与 v1_1_0 硬编码逐字一致`() {
        val zh = ZhChromeStrings
        assertEquals("已关闭标签", zh.snackTabClosed)
        assertEquals("撤销", zh.snackUndo)
        assertEquals("已关闭其他标签", zh.snackOthersClosed)
        assertEquals("已关闭全部标签", zh.snackAllClosed)
        assertEquals("已切换桌面版网站", zh.snackDesktopModeOn)
        assertEquals("已切换回移动版", zh.snackDesktopModeOff)
        assertEquals("剪贴板 · 粘贴即走", zh.suggestionClipboardHint)
        assertEquals("网址", zh.suggestionUrlLabel)
        assertEquals("搜索", zh.suggestionSearchLabel)
        assertEquals("确认", zh.jsDialogConfirmLabel)
        assertEquals("拒绝", zh.jsDialogDenyLabel)
        assertEquals("关闭", zh.jsDialogDismissLabel)
        assertEquals("切换 · example.com", zh.tabSwitchHint("example.com"))
    }

    @Test
    fun `中文实现 13 条全部非空`() {
        val values = allStrings(ZhChromeStrings)
        assertEquals(13, values.size)
        values.forEachIndexed { i, v ->
            assertTrue("Zh 第 $i 条文案为空", v.isNotBlank())
        }
    }

    @Test
    fun `英文实现 13 条全部非空`() {
        val values = allStrings(EnglishChromeStrings)
        assertEquals(13, values.size)
        values.forEachIndexed { i, v ->
            assertTrue("English 第 $i 条文案为空", v.isNotBlank())
        }
    }

    @Test
    fun `中英两实现互不同文（英文不是中文的复读）`() {
        val zh = allStrings(ZhChromeStrings)
        val en = allStrings(EnglishChromeStrings)
        zh.zip(en).forEachIndexed { i, (z, e) ->
            assertTrue("第 $i 条中英文案相同（疑似未翻译）：$z", z != e)
        }
    }

    @Test
    fun `tabSwitchHint 必须包含 host`() {
        listOf(ZhChromeStrings, EnglishChromeStrings, ChromeStrings.DEFAULT).forEach { s ->
            val hint = s.tabSwitchHint("news.example.com")
            assertTrue("hint 缺 host：$hint", hint.contains("news.example.com"))
        }
    }

    @Test
    fun `DEFAULT 即中文实现（默认行为零变化）`() {
        assertEquals(ZhChromeStrings, ChromeStrings.DEFAULT)
    }

    @Test
    fun `接口抽象成员数锁定 13（新增成员须同步两实现与本测试）`() {
        // 12 个 val 属性（→ 12 个抽象 getter）+ 1 个带参 fun = 13。
        // 过滤 synthetic（桥方法）与非抽象（companion 静态访问器等），
        // 只数接口自身声明的抽象成员。
        val declared = ChromeStrings::class.java.declaredMethods
            .filter { !it.isSynthetic && Modifier.isAbstract(it.modifiers) }
        assertEquals(13, declared.size)
    }
}
