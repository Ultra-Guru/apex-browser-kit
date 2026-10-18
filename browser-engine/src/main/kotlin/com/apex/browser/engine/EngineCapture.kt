package com.apex.browser.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import com.apex.browser.core.BrowserScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/**
 * 全页截图（v1.7.0）——[BrowserEngine.screenshot] 的视口版补充。
 *
 * 动机：视口截图对「验证码 / 局部控件」够用，但「整页版式审查 / 长图
 * 留档 / 电商详情页视觉比对」需要全页。Playwright 的
 * `page.screenshot({ fullPage: true })` 是桌面基线；WebView 没有原生
 * 等价物，本扩展用「滚动分段 + Canvas 拼接」实现同语义。
 *
 * 实现为 [BrowserEngine] 的扩展函数（而非成员）：引擎主文件已贴满
 * 1200 非注释行预算（GATE 1）；且本能力只依赖引擎**公开面**
 * （[BrowserEngine.activeWebView] / [BrowserEngine.executeJavaScript]），
 * 拆到同包独立文件零成本。
 *
 * 已知取舍（诚实文档，README v1.7.0 同步）：
 * 1. **sticky/fixed 元素在每段重复**（顶栏会在拼接图里出现 N 次）——
 *    Canvas 快照式全页截图的固有形态（Playwright 用 CDP 越过渲染层，
 *    WebView 无此通道）；
 * 2. **分段上限 [DEFAULT_MAX_SEGMENTS] = 5**：ARGB_8888 位图
 *    width×height×4 字节，1080×5 视口高≈20MB，再放大先撞 OOM 而非
 *    预算；createBitmap 全程 runCatching，OOM 返回 null（调用方拿到的
 *    是「截不了」而非崩溃）；
 * 3. 段间 [SEGMENT_SETTLE_MS] 等待渲染稳定——懒加载页可能只拍到占位图
 *   （与 Playwright 的 lazy 等待不同源，无 DOMContentLoaded 信号可用）。
 */
suspend fun BrowserEngine.fullPageScreenshot(
    maxSegments: Int = DEFAULT_MAX_SEGMENTS,
    onProgress: ((percent: Int, phase: String) -> Unit)? = null,
): ByteArray? = withContext(Dispatchers.Main) {
    val wv = activeWebView() ?: return@withContext null
    if (wv.width <= 0 || wv.height <= 0) {
        onProgress?.invoke(100, "视口尚未布局（后台模式），全页截图跳过")
        return@withContext null
    }
    onProgress?.invoke(5, "读取页面几何…")

    // (1) 页面几何（CSS 像素口径）
    val geometry = readPageGeometry(wv) ?: run {
        onProgress?.invoke(100, "页面几何读取失败（脚本被页面拦截）")
        return@withContext null
    }
    if (geometry.scrollHeightPx <= 0 || geometry.viewportH <= 0) {
        onProgress?.invoke(100, "页面无可滚动内容，退回视口截图")
        return@withContext null
    }

    // (2) 分段规划：CSS 视口高 → 视图像素高换算（与 cssScaleFactor 同口径的实测法）
    val scale = if (geometry.viewportW > 0f) wv.width.toFloat() / geometry.viewportW
    else wv.resources.displayMetrics.density
    val totalDocHeightPx = (geometry.scrollHeightPx * scale).toInt()
    val segmentHeightPx = wv.height
    val cappedHeight = totalDocHeightPx
        .coerceAtMost(segmentHeightPx * maxSegments.coerceIn(1, HARD_MAX_SEGMENTS))
        .coerceAtMost(MAX_BITMAP_HEIGHT_PX)
    val segments = ((cappedHeight + segmentHeightPx - 1) / segmentHeightPx).coerceAtLeast(1)
    val originalScrollY = geometry.scrollY

    // (3) 拼接画布（OOM 防线：runCatching → null）
    val bitmap = runCatching {
        Bitmap.createBitmap(wv.width, cappedHeight, Bitmap.Config.ARGB_8888)
    }.getOrNull() ?: run {
        onProgress?.invoke(100, "全页位图分配失败（页面过长，OOM 防线）")
        return@withContext null
    }
    val canvas = Canvas(bitmap)
    try {
        for (seg in 0 until segments) {
            // viewportH 是 CSS 浮点；绝对滚动坐标取整（1px 内误差由段间覆盖裁掉）
            val targetY = (seg * geometry.viewportH).toInt()
            onProgress?.invoke(
                10 + (seg * 80 / segments),
                "截取第 ${seg + 1}/$segments 段…",
            )
            runCatching { executeJavaScript(BrowserScript.scrollToYJs(targetY)) }
            delay(SEGMENT_SETTLE_MS)
            // 段内容落到画布 y = seg×段高（CSS 滚动位 targetY × scale 与之对齐；
            // 取整误差 1~2px 由末段覆盖裁掉）
            canvas.save()
            canvas.translate(0f, (seg * segmentHeightPx).toFloat())
            wv.draw(canvas)
            canvas.restore()
        }
    } finally {
        // (4) 恢复原始滚动位置（不留副作用）
        runCatching { executeJavaScript(BrowserScript.scrollToYJs(originalScrollY)) }
    }

    onProgress?.invoke(95, "压缩 PNG…")
    val stream = java.io.ByteArrayOutputStream()
    val ok = runCatching { bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream) }.getOrDefault(false)
    val bytes = if (ok) stream.toByteArray() else null
    bitmap.recycle()
    onProgress?.invoke(100, if (bytes != null) "全页截图完成（$segments 段拼接）" else "PNG 压缩失败")
    bytes
}

/** [fullPageScreenshot] 的几何读数（CSS 像素）。 */
private class PageGeometry(
    val scrollY: Int,
    val scrollHeightPx: Int,
    val viewportW: Float,
    val viewportH: Float,
)

/** evaluateJavascript 回传的 JSON 编码外壳剥除 + 解析（与引擎 unwrapWebViewJson 同口径）。 */
private suspend fun readPageGeometry(wv: WebView): PageGeometry? {
    val raw = runCatching { wv.evaluateAwait(BrowserScript.PAGE_GEOMETRY_JS) }.getOrNull() ?: return null
    return runCatching {
        val json = Json { ignoreUnknownKeys = true }
        // raw 是「JSON 字符串字面量」编码（脚本以 stringify 收尾）→ 剥一层
        val inner = json.parseToJsonElement(raw).jsonPrimitive.content
        val obj = json.parseToJsonElement(inner).jsonObject
        PageGeometry(
            scrollY = obj["scrollY"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            scrollHeightPx = obj["scrollHeight"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            viewportW = obj["viewportW"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
            viewportH = obj["viewportH"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
        )
    }.getOrNull()
}

/** 挂起式 evaluateJavascript（一次性桥接，引擎私有封装不可见于扩展 → 本地实现）。 */
private suspend fun WebView.evaluateAwait(script: String): String? =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { result -> cont.resume(result ?: "null") }
    }

/** 分段默认值：5 段（≈5 屏，1080 宽下位图 ≈ 20MB 上界）。 */
const val DEFAULT_MAX_SEGMENTS: Int = 5

/** 分段硬上限：8（防调用方传天文数字绕过 OOM 防线）。 */
const val HARD_MAX_SEGMENTS: Int = 8

/** 拼接位图总高硬上限（像素）：4096*1.5 ≈ 常见设备安全线。 */
const val MAX_BITMAP_HEIGHT_PX: Int = 6144

/** 段间渲染稳定等待：懒加载/无限滚动页的最低妥协。 */
const val SEGMENT_SETTLE_MS: Long = 350
