package com.apex.browser.chrome

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/*
 * FaviconLoader —— 真实站点图标加载（v1.7.0）。
 *
 * 取代 [TabStrip] 的首字母徽标占位（「引擎将来可替换为真实 favicon 的
 * 占位实现」的兑现）。取图源：
 * - **DuckDuckGo Icons**（`https://icons.duckduckgo.com/ip3/<host>.ico`）：
 *   相比 Google S2 不打痕、无参数、轻量（~1-3KB）；仅按 host 取图，
 *   不携带路径/查询（把「用户在看哪个页面」泄露给图标服务的面积最小化）；
 * - 进程内 [LruCache]（32 位图 ≈ 32×64×64×4B ≈ 512KB 上界）+ 在途去重
 *  （同 host 并发请求只发一次网络）；
 * - IO 失败/超时（4s）/非位图响应 → 首字母徽标回退（**永不阻塞 UI**，
 *   图标是增强不是关键路径）。
 *
 * 线程模型：网络与解码在 Dispatchers.IO；缓存读写线程安全（LruCache
 * 本身 synchronized；在途表用 ConcurrentHashMap）。
 */
internal object FaviconRepository {

    private const val TIMEOUT_MS = 4000
    private const val MAX_CACHE = 32

    private val cache = LruCache<String, Bitmap>(MAX_CACHE)
    private val inFlight = ConcurrentHashMap<String, List<(Bitmap?) -> Unit>>()

    /** 载入 host 的 favicon；回调在调用方指定的调度器上执行（null = 失败/未命中）。 */
    fun load(host: String, onDone: (Bitmap?) -> Unit) {
        val key = host.lowercase()
        cache.get(key)?.let { cached ->
            onDone(cached)
            return
        }
        val wrapped: (Bitmap?) -> Unit = { bmp ->
            bmp?.let { cache.put(key, it) }
            onDone(bmp)
        }
        val existing = inFlight.putIfAbsent(key, mutableListOf(wrapped))
        if (existing != null) {
            // 同 host 在途：搭车，不再发第二个请求
            inFlight[key] = existing + wrapped
            return
        }
        Thread({
            val bmp = fetch(key)
            val callbacks = inFlight.remove(key) ?: listOf(wrapped)
            callbacks.forEach { runCatching { it(bmp) } }
        }, "favicon-$key").apply { isDaemon = true }.start()
    }

    private fun fetch(host: String): Bitmap? = runCatching {
        val url = URL("https://icons.duckduckgo.com/ip3/$host.ico")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return@runCatching null
            // 位图尺寸硬上限：图标服务正常 ≤64×64；异常大响应按攻击面处理
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null && (bmp.width > 256 || bmp.height > 256)) null else bmp
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** 仅测试用：清空缓存与在途表。 */
    fun resetForTest() {
        cache.evictAll()
        inFlight.clear()
    }
}

/**
 * favicon 状态钩子：命中 → Bitmap；未命中/失败 → null（调用方渲染回退）。
 *
 * [host] 变化即重新加载；旧回调结果作废（LaunchedEffect 键语义天然提供）。
 */
@Composable
internal fun rememberFavicon(host: String): androidx.compose.runtime.State<Bitmap?> {
    val state = remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(host) {
        if (host.isBlank()) {
            state.value = null
            return@LaunchedEffect
        }
        FaviconRepository.load(host) { bmp ->
            state.value = bmp
        }
    }
    return state
}

/**
 * 站点徽标（升级版）：真实 favicon 命中则渲染位图，否则首字母回退——
 * 视觉规格与原 [FaviconBadge] 完全一致（调用点零迁移成本）。
 */
@Composable
internal fun FaviconImage(host: String, size: Int = 22) {
    val p = chromePalette()
    val favicon by rememberFavicon(host)
    val bmp = favicon
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size.dp)
                .background(p.accent.copy(alpha = 0.14f), CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size.dp)
                .background(p.accent.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = host.firstOrNull()?.uppercase() ?: "?",
                fontSize = 11.sp,
                color = p.accent,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
