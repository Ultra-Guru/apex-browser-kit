package com.apex.browser.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/*
 * BookmarksSheet —— 书签面板（v1.7.0）。
 *
 * 数据：gateway.bookmarksOrNull()（引擎侧 BookmarkStore 的 MRU 快照流）。
 * 本地过滤（搜索框 + 文件夹片）在 Compose 侧做——书签 ≤500 条，
 * 客户端过滤的复杂度远低于把过滤下推引擎再开一条命令通道。
 *
 * 交互：
 * - 行点击 = 打开（touch MRU + loadUrl + 关面板）；
 * - 行尾删除（controller.removeBookmark 带轻提示）；
 * - 头部「收藏当前页」（controller.addBookmarkForCurrentPage，重复页自动更新）；
 * - 文件夹片（根目录固定首位）过滤列表。
 */
@Composable
internal fun BookmarksSheet(
    ui: ChromeUiState,
    gateway: BrowserEngineGateway,
    controller: BrowserChromeController,
) {
    val bookmarksFlow = gateway.bookmarksOrNull()
    if (bookmarksFlow == null) return
    val visible = ui.sheet == ChromeSheet.BOOKMARKS
    if (!visible) return

    val p = chromePalette()
    val bookmarks by bookmarksFlow.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var folderFilter by remember { mutableStateOf<String?>(null) }

    // 文件夹聚合（根目录归一为 null 过滤位）
    val folders = remember(bookmarks) {
        val grouped = bookmarks.groupBy { it.folder }
        buildList {
            grouped[""]?.let { add(null to it.size) }
            grouped.entries.filter { it.key.isNotBlank() }
                .sortedBy { it.key }
                .forEach { add(it.key to it.value.size) }
        }
    }

    ChromeBottomSheet(visible = true, onDismiss = { controller.closeSheet() }) {
        SheetGrabBar()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.browser_bookmarks_title),
                fontSize = 15.sp,
                color = p.textPrimary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { controller.addBookmarkForCurrentPage() },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.BookmarkAdd,
                    contentDescription = stringResource(R.string.browser_bookmarks_add_current),
                    tint = p.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // 搜索框（自绘 BasicTextField：与 chrome 暗色玻璃体系一致，无 Material 默认底色）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .background(p.background, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = p.textSecondary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = p.textPrimary),
                cursorBrush = SolidColor(p.accent),
                modifier = Modifier.weight(1f),
            ) {
                if (query.isEmpty()) {
                    Text(
                        stringResource(R.string.browser_bookmarks_search_hint),
                        fontSize = 13.sp,
                        color = p.textSecondary,
                    )
                }
            }
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { query = "" },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.browser_addr_clear),
                        tint = p.textSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        // 文件夹片
        if (folders.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                folders.forEach { (name, count) ->
                    val selected = folderFilter == name
                    Box(
                        modifier = Modifier
                            .background(
                                if (selected) p.accent.copy(alpha = 0.22f) else p.background,
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { folderFilter = if (selected) null else name }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = (name ?: stringResource(R.string.browser_bookmarks_folder_root)) + " · $count",
                            fontSize = 11.sp,
                            color = if (selected) p.accent else p.textSecondary,
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = p.stroke, modifier = Modifier.padding(vertical = 6.dp))

        if (bookmarks.isEmpty()) {
            Text(
                text = stringResource(R.string.browser_bookmarks_empty),
                fontSize = 12.sp,
                color = p.textSecondary,
                modifier = Modifier.padding(18.dp),
            )
        } else {
            val filtered = remember(bookmarks, query, folderFilter) {
                bookmarks.filter { bm ->
                    (folderFilter == null || bm.folder == folderFilter) &&
                        (query.isBlank() ||
                            bm.title.contains(query, ignoreCase = true) ||
                            bm.host.contains(query, ignoreCase = true) ||
                            bm.url.contains(query, ignoreCase = true))
                }
            }
            if (filtered.isEmpty()) {
                Text(
                    text = stringResource(R.string.browser_bookmarks_no_match),
                    fontSize = 12.sp,
                    color = p.textSecondary,
                    modifier = Modifier.padding(18.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                ) {
                    items(filtered, key = { it.id }) { bm ->
                        BookmarkRow(
                            bookmark = bm,
                            onClick = { controller.openBookmark(bm) },
                            onDelete = { controller.removeBookmark(bm.id) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun BookmarkRow(
    bookmark: ChromeBookmark,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val p = chromePalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FaviconImage(host = bookmark.host, size = 26)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bookmark.title.ifBlank { bookmark.host },
                fontSize = 13.sp,
                color = p.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = bookmark.host,
                fontSize = 10.sp,
                color = p.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.browser_bookmark_delete),
                tint = p.textSecondary.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
