package com.apex.browser.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.apex.browser.core.ReaderBlock
import com.apex.browser.core.ReaderBlockKind
import com.apex.browser.core.ReaderDocument

/*
 * ReaderSheet —— 阅读模式全屏层（v1.7.0）。
 *
 * 数据：gateway.renderReaderPage()（引擎 READER_DOCUMENT_JS 采集 →
 * ReaderParser 结构化）。三态本地模型：Loading / Ready / Unavailable
 * （页面无结构化正文——工具页/空页，属正常降级不是错误）。
 *
 * 排版控制（纯本地状态，不回写引擎）：
 * - 字号 ±（0.8f..1.6f 线性步进 0.1f，跨打开记住于本次会话）；
 * - 目录条（横向章节片，点按滚动到对应块——LazyColumn animateScrollToItem）。
 *
 * 全屏自绘（不进 ChromeBottomSheet/ScrimDialog 体系）：阅读是「沉浸面」
 * 不是「浮层面」；背景直接用 palette.background 全铺。
 */
@Composable
internal fun ReaderSheet(
    visible: Boolean,
    gateway: BrowserEngineGateway,
    controller: BrowserChromeController,
) {
    if (!visible) return
    val p = chromePalette()

    // 三态采集：打开即取，页面切换重开由 controller.openReaderMode() 重新进入
    var doc by remember { mutableStateOf<ReaderDocument?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        loading = true
        doc = runCatching { gateway.renderReaderPage() }.getOrNull()
        loading = false
    }

    var fontScale by remember { mutableFloatStateOf(1f) }
    val listState = rememberLazyListState()
    // 目录锚点跳转桥：clickable 是非挂起上下文，滚动经挂起效应执行
    var pendingAnchor by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingAnchor) {
        pendingAnchor?.let { idx -> runCatching { listState.animateScrollToItem(idx) } }
        pendingAnchor = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background)
            .statusBarsPadding(),
    ) {
        // ── 顶栏：关闭 / 标题 / 字号控制 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { controller.closeReaderMode() }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.browser_reader_close),
                    tint = p.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = doc?.title ?: stringResource(R.string.browser_reader_title),
                fontSize = 14.sp,
                color = p.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )
            IconButton(
                onClick = { fontScale = (fontScale - 0.1f).coerceAtLeast(0.8f) },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Filled.Remove,
                    contentDescription = stringResource(R.string.browser_reader_font_smaller),
                    tint = p.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = "${(fontScale * 100).toInt()}%",
                fontSize = 11.sp,
                color = p.textSecondary,
            )
            IconButton(
                onClick = { fontScale = (fontScale + 0.1f).coerceAtMost(1.6f) },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.browser_reader_font_larger),
                    tint = p.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = p.accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }

            doc == null -> ReaderUnavailable(controller = controller)

            else -> {
                val d = doc!!
                // ── 元信息条：站点 / 作者 / 发布时间 / 预计阅读 ──
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                    val meta = listOfNotNull(
                        d.siteName.takeIf { it.isNotBlank() },
                        d.byline.takeIf { it.isNotBlank() },
                        d.publishedTime.takeIf { it.isNotBlank() },
                    ).joinToString(separator = " · ")
                    Text(
                        text = stringResource(R.string.browser_reader_minutes, d.readingMinutes) +
                            if (meta.isBlank()) "" else " · $meta",
                        fontSize = 11.sp,
                        color = p.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // ── 目录条（横向章节片，点按跳块） ──
                if (d.chapters.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        d.chapters.forEach { ch ->
                            Box(
                                modifier = Modifier
                                    .background(p.surface, RoundedCornerShape(8.dp))
                                    .clickable { pendingAnchor = ch.blockIndex }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    text = ch.title,
                                    fontSize = 11.sp,
                                    color = p.accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                // ── 正文块列表 ──
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 18.dp, vertical = 10.dp,
                    ),
                ) {
                    itemsIndexed(d.blocks, key = { i, b -> "$i:${b.kind}:${b.text.hashCode()}" }) { _, block ->
                        ReaderBlockView(block = block, fontScale = fontScale)
                    }
                    item { Spacer(Modifier.height(28.dp)) }
                }
            }
        }
    }
}

/** 页面不适合阅读模式：说明 + 关闭（正常降级路径，不是错误态）。 */
@Composable
private fun ReaderUnavailable(controller: BrowserChromeController) {
    val p = chromePalette()
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            tint = p.textSecondary.copy(alpha = 0.5f),
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.browser_reader_empty),
            fontSize = 13.sp,
            color = p.textSecondary,
        )
    }
}

/** 单个阅读块的排版分发（heading/paragraph/quote/code/image/list_item）。 */
@Composable
private fun ReaderBlockView(
    block: ReaderBlock,
    fontScale: Float,
) {
    val p = chromePalette()
    val base = 14.sp * fontScale
    when (block.kind) {
        ReaderBlockKind.HEADING -> Text(
            text = block.text,
            fontSize = (base.value * (1.9f - block.level * 0.15f)).sp,
            color = p.textPrimary,
            fontWeight = FontWeight.Bold,
            lineHeight = (base.value * 1.5f).sp,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
        )
        ReaderBlockKind.PARAGRAPH -> Text(
            text = block.text,
            fontSize = base,
            color = p.textPrimary,
            lineHeight = (base.value * 1.75f).sp,
            modifier = Modifier.padding(vertical = 5.dp),
        )
        ReaderBlockKind.QUOTE -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .background(p.surface.copy(alpha = 0.6f), RoundedCornerShape(6.dp)),
        ) {
            Text(
                text = block.text,
                fontSize = base,
                color = p.textSecondary,
                lineHeight = (base.value * 1.7f).sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        ReaderBlockKind.CODE -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .background(p.background, RoundedCornerShape(6.dp)),
        ) {
            Text(
                text = block.text,
                fontSize = (base.value * 0.85f).sp,
                color = p.accent,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.padding(10.dp),
            )
        }
        ReaderBlockKind.IMAGE -> Text(
            text = stringResource(
                R.string.browser_reader_image,
                block.alt.ifBlank { block.srcSummary.ifBlank { "-" } },
            ),
            fontSize = 11.sp,
            color = p.textSecondary,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        ReaderBlockKind.LIST_ITEM -> Row(
            modifier = Modifier.padding(vertical = 2.dp),
        ) {
            Text("·  ", fontSize = base, color = p.accent)
            Text(
                text = block.text,
                fontSize = base,
                color = p.textPrimary,
                lineHeight = (base.value * 1.7f).sp,
            )
        }
    }
}
