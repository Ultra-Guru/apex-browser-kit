package com.apex.browser.engine

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import com.apex.browser.core.DownloadLedger
import com.apex.browser.core.DownloadLedgerCodec
import com.apex.browser.core.DownloadLedgerEntry
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * DownloadCenter —— Agent 面向的下载查询与控制中心（v1.7.0）。
 *
 * 解决的缺口：引擎只暴露 `lastDownload`（**最近一次**下载的瞬时值），
 * Playwright / 桌面浏览器基线里的「列出全部下载 / 暂停 / 恢复 / 重试 /
 * 进程重启后仍可查询」全部缺位。本中心：
 * 1. 把 (dmId, fileName, url, mime) 持久化为**台账**
 *   （[DownloadLedger]，SharedPreferences）——进程重启后凭 ID 续查；
 * 2. 双下载源吸收：chrome 人用下载（wiring.onEnqueued 回报）+ Agent
 *    驾驶期引擎下载（[engine].[BrowserEngine.lastDownload] 轮询吸收）；
 * 3. 系统 DownloadManager 的 Cursor 查询翻译成 [DownloadStatus]
 *   （状态 / 字节 / 进度 / 失败原因）；
 * 4. pause / resume / cancel / retry / open 控制（API 24+，minSdk 26 满足）。
 *
 * 与 chrome 层 `ChromeDownloadManager` 的分工（不重叠）：
 * - ChromeDownloadManager：chrome UI 的**活动下载进度条**（只跟自己入队的，
 *   500ms 轮询，UI 即时消费）；
 * - DownloadCenter：**全量台账 + 生命周期控制**（Agent 工具 / 下载管理
 *   面板 / 跨重启连续性）。
 *
 * 线程模型：公开方法 `@Synchronized`（chrome 主线程 / Agent IO 双源并发）；
 * 轮询协程在 Dispatchers.Default（Cursor 查询是轻量 IPC，避免占用主线程）。
 */
class DownloadCenter private constructor(
    appContext: Context,
    private val engine: BrowserEngine,
) {

    private val appContext: Context = appContext.applicationContext

    /** 下载状态（DownloadManager.STATUS_* 的语义翻译 + 台账字段）。 */
    data class DownloadStatus(
        val dmId: Long,
        val fileName: String,
        val url: String,
        val mimeType: String?,
        /** QUEUED / RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED。 */
        val state: String,
        val bytesSoFar: Long = 0L,
        val totalBytes: Long = -1L,
        /** FAILED 时的系统 reason 码（DownloadManager.ERROR_*）。 */
        val reason: Int = 0,
        val enqueuedAt: Long = 0L,
    ) {
        /** 0..100，totalBytes 未知（-1）时按字节近似展示。 */
        val percent: Int
            get() = if (totalBytes > 0) ((bytesSoFar * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

        val isTerminal: Boolean
            get() = state == STATE_COMPLETED || state == STATE_FAILED || state == STATE_CANCELLED
    }

    private val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.w(TAG, "download-center task failed", e)
        }
    )

    private val ledger = DownloadLedger(DownloadLedgerCodec.decode(prefs.getString(KEY_LEDGER, null)))

    private val _downloads = MutableStateFlow<List<DownloadStatus>>(emptyList())
    /** 下载快照流（新在前；Agent 工具与潜在 UI 的共用事实源）。 */
    val downloads: StateFlow<List<DownloadStatus>> = _downloads.asStateFlow()

    private var pollJob: Job? = null

    init {
        refresh()
    }

    /* ---------------- 台账入账（chrome 路径 / 引擎路径双源） ---------------- */

    /**
     * chrome 人用下载入队后的回报（[com.apex.browser.chrome.bridge.ApexChromeWiring]
     * 的 wiring.downloads.onEnqueued 转发）。幂等（见 [DownloadLedger.register]）。
     */
    @Synchronized
    fun registerChromeDownload(dmId: Long, fileName: String, url: String, mimeType: String?) {
        if (ledger.register(dmId, fileName, url, mimeType)) persistLedger()
        refresh()
    }

    /**
     * 刷新：Cursor 查询全部台账 ID + 吸收引擎 `lastDownload` 孤儿 + 配额裁剪。
     *
     * 幂等、可重入；任何一步失败都保持旧快照（下载中心不可用 ≠ 下载失败）。
     */
    @Synchronized
    fun refresh() {
        // (1) 引擎驾驶期孤儿吸收：chrome 未接线时下载只写 lastDownload
        engine.lastDownload?.let { last ->
            if (ledger.absorbObserved(last.fileName, last.url, last.downloadId)) persistLedger()
        }
        // (2) Cursor 批量查询
        val statuses = queryAll()
        // (3) 配额裁剪（淘汰最旧）
        val pruned = ledger.prune()
        if (pruned.isNotEmpty()) persistLedger()
        // (4) 快照发布（裁剪掉的不再现）
        _downloads.value = statuses.filter { it.dmId in ledger.ids().toSet() }
        // (5) 有活动下载时保持 500ms 轮询，否则停表（省电）
        ensurePollingIfNeeded()
    }

    /* ---------------- 控制 ---------------- */

    /**
     * 能力口径（诚实文档）：系统 [DownloadManager] 的公开 API **没有**
     * pause/resume（`pauseDownload`/`resumeDownload` 是 @hide，编译不可达）；
     * 系统自身因网络原因暂停时 Cursor 会报 `STATUS_PAUSED`（状态映射保留，
     * 展示层照常可见）。本中心的控制面 = cancel / retry / forget / open。
     */
    /**
     * 取消并删除下载（`DownloadManager.remove` 同时删文件——系统语义，
     * 文档化而非隐藏）。
     */
    @Synchronized
    fun cancel(dmId: Long): Boolean = runCatching {
        dm.remove(dmId)
    }.isSuccess.also { if (it) refresh() }

    /**
     * 重试失败/取消的下载：原 URL 重新入队（转发会话 Cookie——登录态下载
     * 的重试与首次同权），旧 ID 出账、新 ID 入账。
     */
    suspend fun retry(dmId: Long): Long? {
        val entry = synchronized(this) { ledger.find(dmId) } ?: return null
        val cookie = runCatching { engine.getCookies(entry.url) }.getOrNull()
        val newId = enqueue(entry, cookie) ?: return null
        synchronized(this) {
            ledger.remove(dmId)
            if (ledger.register(newId, entry.fileName, entry.url, entry.mimeType)) persistLedger()
            refresh()
        }
        return newId
    }

    /**
     * 打开系统「下载」应用（不是直接打开文件——file:// URI 直开在
     * API 24+ 触发 FileUriExposedException，系统下载 UI 是无 FileProvider
     * 依赖的标准出口，与 chrome 层 ChromeDownloadManager 同款决策）。
     */
    fun openSystemDownloads() {
        runCatching {
            appContext.startActivity(
                Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 按 id 取当前状态（未在 Cursor 中出现 = 已被系统移除 → CANCELLED 语义）。 */
    @Synchronized
    fun statusOf(dmId: Long): DownloadStatus? = _downloads.value.firstOrNull { it.dmId == dmId }

    @Synchronized
    fun snapshot(): List<DownloadStatus> = _downloads.value

    /** 删除一条台账记录（不取消进行中的下载——那是 [cancel] 的职责）。 */
    @Synchronized
    fun forget(dmId: Long): Boolean {
        val ok = ledger.remove(dmId)
        if (ok) {
            persistLedger()
            _downloads.value = _downloads.value.filterNot { it.dmId == dmId }
        }
        return ok
    }

    /* ---------------- 内部 ---------------- */

    private fun queryAll(): List<DownloadStatus> {
        val ids = ledger.ids()
        if (ids.isEmpty()) return emptyList()
        return runCatching {
            val query = DownloadManager.Query().setFilterById(*ids)
            dm.query(query).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) return emptyList()
                val out = ArrayList<DownloadStatus>(cursor.count)
                do {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    val entry = ledger.find(id) ?: continue
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    out.add(
                        DownloadStatus(
                            dmId = id,
                            fileName = entry.fileName,
                            url = entry.url,
                            mimeType = entry.mimeType,
                            state = mapState(status),
                            bytesSoFar = bytes,
                            totalBytes = total,
                            reason = reason,
                            enqueuedAt = entry.enqueuedAt,
                        )
                    )
                } while (cursor.moveToNext())
                out
            }
        }.getOrDefault(emptyList())
    }

    private fun mapState(status: Int): String = when (status) {
        DownloadManager.STATUS_PENDING -> STATE_QUEUED
        DownloadManager.STATUS_RUNNING -> STATE_RUNNING
        DownloadManager.STATUS_PAUSED -> STATE_PAUSED
        DownloadManager.STATUS_SUCCESSFUL -> STATE_COMPLETED
        DownloadManager.STATUS_FAILED -> STATE_FAILED
        else -> STATE_UNKNOWN
    }

    private suspend fun enqueue(entry: DownloadLedgerEntry, cookie: String?): Long? {
        val request = DownloadManager.Request(android.net.Uri.parse(entry.url)).apply {
            entry.mimeType?.let { setMimeType(it) }
            setTitle(entry.fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, entry.fileName)
            cookie?.takeIf { it.isNotBlank() }?.let { addRequestHeader("Cookie", it) }
        }
        return runCatching { dm.enqueue(request) }.getOrNull()
    }

    /** 活动下载存在 → 500ms 轮询直到全部终态（终态即停，不做常驻心跳）。 */
    private fun ensurePollingIfNeeded() {
        val hasActive = _downloads.value.any { !it.isTerminal }
        if (hasActive && pollJob?.isActive != true) {
            pollJob = scope.launch {
                while (isActive) {
                    delay(POLL_ACTIVE_MS)
                    val stillActive = synchronized(this@DownloadCenter) {
                        engine.lastDownload?.let { last ->
                            if (ledger.absorbObserved(last.fileName, last.url, last.downloadId)) persistLedger()
                        }
                        queryAll().also { fresh ->
                            _downloads.value = fresh.filter { it.dmId in ledger.ids().toSet() }
                        }.any { !it.isTerminal }
                    }
                    if (!stillActive) break
                }
            }
        }
    }

    private fun persistLedger() {
        prefs.edit().putString(KEY_LEDGER, DownloadLedgerCodec.encode(ledger.list())).apply()
    }

    companion object {
        private const val TAG = "DownloadCenter"
        private const val PREFS_NAME = "apex_browser_download_ledger"
        private const val KEY_LEDGER = "ledger_v1"
        private const val POLL_ACTIVE_MS = 500L

        const val STATE_QUEUED = "QUEUED"
        const val STATE_RUNNING = "RUNNING"
        const val STATE_PAUSED = "PAUSED"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_FAILED = "FAILED"
        const val STATE_CANCELLED = "CANCELLED"
        private const val STATE_UNKNOWN = "UNKNOWN"

        /**
         * 进程级单例工厂（与 [BookmarkStore] / [BrowserEngineFactory] 同纪律）。
         * chrome 接线与宿主 Agent 工具必须共享同一实例（台账唯一）。
         */
        @Volatile
        private var instance: DownloadCenter? = null

        fun get(context: Context, engine: BrowserEngine): DownloadCenter =
            instance ?: synchronized(this) {
                instance ?: DownloadCenter(context, engine).also { instance = it }
            }

        /** 仅测试用：重置单例。 */
        @Synchronized
        fun reset() {
            instance = null
        }
    }
}

/** 单例工厂的独立宿主（保持文件组织习惯：Factory 紧贴主类）。 */
object DownloadCenterFactory {

    fun get(context: Context, engine: BrowserEngine): DownloadCenter =
        DownloadCenter.get(context, engine)

    @Synchronized
    fun reset() = DownloadCenter.reset()
}
