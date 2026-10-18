package com.apex.browser.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 P1 #9 可观测性 / P1 #8 上下文压缩的「意图」：
 * - 记录多步后，contextSummary 保留最近 3 步详情，更早步骤被压缩为单行。
 * - recent 限制返回条数。
 * - v1.3.0：Entry.error 字段与 events SharedFlow 的实时订阅语义。
 */
class BrowserTracerTest {

    @Test
    fun `contextSummary 压缩早期步骤保留近期详情`() {
        val tracer = BrowserTracer(capacity = 50)
        repeat(10) { i ->
            tracer.record(
                tool = "browser_click",
                params = """{"ref":"r_$i"}""",
                resultSummary = "点击第 $i 步完成",
                durationMs = 100,
                url = "https://x.com/$i",
                state = "AGENT_DRIVING",
            )
        }
        val summary = tracer.contextSummary(maxFullSteps = 3)
        // 近期 3 步应有详情行（含工具名与结果摘要）——记录 i=0..9，最近 3 步为 7/8/9
        //（修复 off-by-one：原断言第 10/8 步，实际只记录到第 9 步）
        assertTrue(summary.contains("点击第 9 步完成"))
        assertTrue(summary.contains("点击第 7 步完成"))
        // 早期步骤被压缩为单行摘要
        assertTrue(summary.contains("早期 7 步压缩"))
        // 仍保留总量信息
        assertTrue(summary.contains("共 10 步"))
    }

    @Test
    fun `recent 限制返回条数`() {
        val tracer = BrowserTracer(capacity = 50)
        repeat(20) { i ->
            tracer.record("t$i", "p", "r", 1, "u", "S")
        }
        assertEquals(5, tracer.recent(5).size)
        assertEquals(20, tracer.recent(100).size)
    }

    // ═══ v1.3.0：error 字段 + events SharedFlow ═══

    @Test
    fun `Entry 携带 error 字段且成功时为 null`() {
        val tracer = BrowserTracer()
        // 不传 error（成功边界）与传 error（失败边界）各一条
        tracer.record("browser_click", """{"ref":"r_1"}""", "ok", 12, "https://x.com", "AGENT_DRIVING")
        tracer.record(
            tool = "browser_input", params = """{"ref":"r_2"}""", resultSummary = "failed",
            durationMs = 340, url = "https://x.com", state = "AGENT_DRIVING",
            error = "ref=r_2 不是可输入元素",
        )
        val recent = tracer.recent(2)
        assertEquals(null, recent[0].error)
        assertEquals("ref=r_2 不是可输入元素", recent[1].error)
    }

    @Test
    fun `events 流在 record 后被订阅者收到`() = runBlocking {
        val tracer = BrowserTracer()
        val collected = java.util.Collections.synchronizedList(mutableListOf<BrowserTracer.Entry>())
        // replay=0：订阅必须先于 record。onSubscription 在注册完成后发信号，
        // await 返回时收集器已就位 —— 之后的 tryEmit 必然投递给该订阅者。
        val subscribed = CompletableDeferred<Unit>()
        val job = launch {
            tracer.events
                .onSubscription { subscribed.complete(Unit) }
                .collect { collected.add(it) }
        }
        subscribed.await()
        repeat(3) { i ->
            tracer.record("t$i", "p$i", "ok", 1, "u", "S", error = if (i == 2) "boom" else null)
        }
        // 投递经 SharedFlow 缓冲异步恢复：轮询等待（上限 2s，超时即失败）
        withTimeout(2000) {
            while (collected.size < 3) delay(10)
        }
        job.cancel()
        assertEquals(3, collected.size)
        assertEquals(listOf("t0", "t1", "t2"), collected.map { it.tool })
        // error 字段随事件透传
        assertEquals(null, collected[0].error)
        assertEquals("boom", collected[2].error)
    }
}
