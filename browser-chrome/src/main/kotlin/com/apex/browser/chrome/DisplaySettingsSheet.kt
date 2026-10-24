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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/*
 * DisplaySettingsSheet —— 显示设置面板（v1.7.0）。
 *
 * 三个分区：
 * 1. 文字缩放：± 步进（10%）+ 快速档位片（85 / 100 / 115 / 130）——
 *    阈值 50..200 与 EngineDisplayController.DisplaySettings 对齐；
 * 2. 夜间模式（算法反色）与桌面版网站开关（后者复用既有菜单语义）；
 * 3. 清除浏览数据：Cookie / 缓存 / 历史 三选 + 确认对话框
 *   （ChromeScrimDialog——不可逆动作要有一个明确的「确认」仪式）。
 *
 * 数据：gateway.displaySettingsOrNull()（未接线则整面板不渲染——与
 * Userscripts/Bookmarks 的可选能力纪律一致）。
 */
@Composable
internal fun DisplaySettingsSheet(
    ui: ChromeUiState,
    gateway: BrowserEngineGateway,
    controller: BrowserChromeController,
) {
    val settingsFlow = gateway.displaySettingsOrNull()
    if (settingsFlow == null) return
    val visible = ui.sheet == ChromeSheet.DISPLAY
    if (!visible) return

    val p = chromePalette()
    val settings by settingsFlow.collectAsStateWithLifecycle()
    var clearCookies by remember { mutableStateOf(true) }
    var clearCaches by remember { mutableStateOf(true) }
    var clearHistories by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    ChromeBottomSheet(visible = true, onDismiss = { controller.closeSheet() }) {
        SheetGrabBar()

        Text(
            text = stringResource(R.string.browser_display_title),
            fontSize = 15.sp,
            color = p.textPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )
        HorizontalDivider(color = p.stroke, modifier = Modifier.padding(vertical = 6.dp))

        // ── 文字缩放 ──
        SettingSectionHeader(
            icon = { Icon(Icons.Filled.TextFields, contentDescription = null, tint = p.accent, modifier = Modifier.size(18.dp)) },
            title = stringResource(R.string.browser_display_text_zoom),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZoomStepButton(label = "−") { controller.setTextZoom(settings.textZoomPercent - 10) }
            Text(
                text = stringResource(R.string.browser_display_text_zoom_value, settings.textZoomPercent),
                fontSize = 14.sp,
                color = p.textPrimary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            ZoomStepButton(label = "+") { controller.setTextZoom(settings.textZoomPercent + 10) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(85, 100, 115, 130).forEach { preset ->
                val selected = settings.textZoomPercent == preset
                Box(
                    modifier = Modifier
                        .background(
                            if (selected) p.accent.copy(alpha = 0.22f) else p.background,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { controller.setTextZoom(preset) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "$preset%",
                        fontSize = 11.sp,
                        color = if (selected) p.accent else p.textSecondary,
                    )
                }
            }
        }

        HorizontalDivider(color = p.stroke, modifier = Modifier.padding(vertical = 6.dp))

        // ── 夜间模式 / 桌面版 ──
        SettingToggleRow(
            icon = { Icon(Icons.Filled.DarkMode, contentDescription = null, tint = p.accentAlt, modifier = Modifier.size(18.dp)) },
            title = stringResource(R.string.browser_display_force_dark),
            subtitle = stringResource(R.string.browser_display_force_dark_desc),
            checked = settings.forceDark,
            onChecked = { enabled -> gateway.setForceDark(enabled) },
        )
        SettingToggleRow(
            icon = { Icon(Icons.Filled.DesktopWindows, contentDescription = null, tint = p.textSecondary, modifier = Modifier.size(18.dp)) },
            title = stringResource(R.string.browser_menu_desktop_mode),
            subtitle = null,
            checked = ui.desktopMode,
            onChecked = { controller.toggleDesktopMode() },
        )

        HorizontalDivider(color = p.stroke, modifier = Modifier.padding(vertical = 6.dp))

        // ── 清除浏览数据 ──
        SettingSectionHeader(
            icon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = p.danger, modifier = Modifier.size(18.dp)) },
            title = stringResource(R.string.browser_display_clear_data),
        )
        ClearDataCheckRow(
            label = stringResource(R.string.browser_clear_cookies),
            desc = stringResource(R.string.browser_clear_cookies_desc),
            checked = clearCookies,
            onChecked = { clearCookies = it },
        )
        ClearDataCheckRow(
            label = stringResource(R.string.browser_clear_caches),
            desc = stringResource(R.string.browser_clear_caches_desc),
            checked = clearCaches,
            onChecked = { clearCaches = it },
        )
        ClearDataCheckRow(
            label = stringResource(R.string.browser_clear_histories),
            desc = stringResource(R.string.browser_clear_histories_desc),
            checked = clearHistories,
            onChecked = { clearHistories = it },
        )
        Button(
            onClick = { confirmClear = true },
            enabled = clearCookies || clearCaches || clearHistories,
            colors = ButtonDefaults.buttonColors(
                containerColor = p.danger.copy(alpha = 0.85f),
                contentColor = p.textPrimary,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.browser_clear_confirm), fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
    }

    // 清除确认对话框（不可逆动作的仪式感）
    if (confirmClear) {
        ChromeScrimDialog(onDismiss = { confirmClear = false }) {
            Text(
                text = stringResource(R.string.browser_clear_confirm_title),
                fontSize = 15.sp,
                color = p.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.browser_clear_confirm_desc),
                fontSize = 12.sp,
                color = p.textSecondary,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = { confirmClear = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = p.surface, contentColor = p.textPrimary,
                    ),
                    modifier = Modifier.padding(end = 8.dp),
                ) { Text(stringResource(R.string.browser_js_dialog_cancel), fontSize = 13.sp) }
                Button(
                    onClick = {
                        confirmClear = false
                        controller.clearBrowsingData(clearCookies, clearCaches, clearHistories)
                        controller.closeSheet()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = p.danger.copy(alpha = 0.85f), contentColor = p.textPrimary,
                    ),
                ) { Text(stringResource(R.string.browser_clear_confirm), fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun SettingSectionHeader(icon: @Composable () -> Unit, title: String) {
    val p = chromePalette()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 13.sp, color = p.textPrimary, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ZoomStepButton(label: String, onClick: () -> Unit) {
    val p = chromePalette()
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(p.background, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 16.sp, color = p.accent, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingToggleRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    val p = chromePalette()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = p.textPrimary)
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 10.sp,
                    color = p.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun ClearDataCheckRow(
    label: String,
    desc: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    val p = chromePalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .padding(horizontal = 18.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(label, fontSize = 13.sp, color = p.textPrimary)
            Text(
                desc,
                fontSize = 10.sp,
                color = p.textSecondary,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}
