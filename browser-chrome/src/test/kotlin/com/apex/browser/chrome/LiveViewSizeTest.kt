package com.apex.browser.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LiveViewOverlay v1.6.0 窗口尺寸可调 + 自适应轮询的纯函数契约锁。
 *
 * View 层（WindowManager/手势）无法在 JVM 单测覆盖 —— 把可测语义收敛到
 * companion 纯函数（[LiveViewOverlay.pollIntervalFor] / [LiveViewOverlay.clampSizeRatio]）
 * 与常量钳位区间，本测试锁定：
 * - 轮询档位：加载中（progress 0..99）快档 500ms，静止/未知 ≥100 走慢档 2s；
 * - 尺寸钳位：拖拽比例被饱和进 [MIN, MAX] 区间（越界钳回、界内原样）；
 * - 默认值与旧 v1.4.0 形态一致（85% × 60%）—— 升级零观感突变。
 */
class LiveViewSizeTest {

    // ── 自适应轮询档位 ──

    @Test fun `poll interval is fast while page is loading`() {
        assertEquals(500L, LiveViewOverlay.POLL_ACTIVE_MS)
        for (p in intArrayOf(0, 1, 37, 99)) {
            assertEquals("progress=$p 应为快档", 500L, LiveViewOverlay.pollIntervalFor(p))
        }
    }

    @Test fun `poll interval is slow when page is idle`() {
        assertEquals(2_000L, LiveViewOverlay.POLL_IDLE_MS)
        for (p in intArrayOf(100, 101, -1)) {
            assertEquals("progress=$p 应为慢档", 2_000L, LiveViewOverlay.pollIntervalFor(p))
        }
    }

    @Test fun `poll interval for invalid progress falls back to slow tier`() {
        // WebView.progress 理论只在 0..100；越界值（null WebView → 100 兜底前）
        // 不得误入快档造成无谓唤醒。
        assertEquals(2_000L, LiveViewOverlay.pollIntervalFor(-5))
        assertEquals(2_000L, LiveViewOverlay.pollIntervalFor(2_000))
    }

    // ── 尺寸比例钳位 ──

    @Test fun `size ratio clamps into bounds`() {
        val min = LiveViewOverlay.MIN_WIDTH_RATIO
        val max = LiveViewOverlay.MAX_WIDTH_RATIO
        assertTrue(min < max)
        assertEquals(min, LiveViewOverlay.clampSizeRatio(0.01f, min, max), 0f)
        assertEquals(max, LiveViewOverlay.clampSizeRatio(1.5f, min, max), 0f)
        assertEquals(0.6f, LiveViewOverlay.clampSizeRatio(0.6f, min, max), 0f)
    }

    @Test fun `default size ratios keep the v1_4_0 form`() {
        assertEquals(0.85f, LiveViewOverlay.DEFAULT_WIDTH_RATIO, 0f)
        assertEquals(0.60f, LiveViewOverlay.DEFAULT_HEIGHT_RATIO, 0f)
        // 「小窗」语义下限：宽 ≥ 35%、高 ≥ 25%；上限不贴满全屏（≤95% × ≤85%）
        assertEquals(0.35f, LiveViewOverlay.MIN_WIDTH_RATIO, 0f)
        assertEquals(0.25f, LiveViewOverlay.MIN_HEIGHT_RATIO, 0f)
        assertEquals(0.95f, LiveViewOverlay.MAX_WIDTH_RATIO, 0f)
        assertEquals(0.85f, LiveViewOverlay.MAX_HEIGHT_RATIO, 0f)
    }

    @Test fun `params apply throttle stays frame aligned`() {
        // ~60fps 帧对齐：两次 updateViewLayout 之间至少 16ms
        assertEquals(16L, LiveViewOverlay.PARAM_APPLY_MIN_INTERVAL_MS)
    }
}
