package com.apex.browser.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 纯逻辑单测：in-app 承载仲裁器（JVM 即可运行，无需设备）。
 * 锁住 v1.5.0 内嵌浏览器与两个悬浮窗之间的互斥契约：
 * 1. claim/release 迁移只会通知一次（幂等重复调用静默）；
 * 2. 监听者注销后不再收通知；
 * 3. 回调内注销自己（CopyOnWrite 快照语义）不抛 CME；
 * 4. 回调抛异常不向其他监听者传播（runCatching 兜底纪律）。
 */
class InAppHostingArbiterTest {

    @Test
    fun `初始态 - 未承载`() {
        InAppHostingArbiter.resetForTest()
        assertFalse(InAppHostingArbiter.isInAppHosting)
    }

    @Test
    fun `claim - 置位并通知一次`() {
        InAppHostingArbiter.resetForTest()
        val seen = mutableListOf<Boolean>()
        val listener = InAppHostingArbiter.Listener { seen.add(it) }
        InAppHostingArbiter.addListener(listener)

        InAppHostingArbiter.claim()
        assertTrue(InAppHostingArbiter.isInAppHosting)
        assertEquals(listOf(true), seen)

        // 幂等：重复 claim 不再通知
        InAppHostingArbiter.claim()
        assertEquals(listOf(true), seen)

        InAppHostingArbiter.removeListener(listener)
    }

    @Test
    fun `release - 复位并通知一次`() {
        InAppHostingArbiter.resetForTest()
        InAppHostingArbiter.claim()
        val seen = mutableListOf<Boolean>()
        val listener = InAppHostingArbiter.Listener { seen.add(it) }
        InAppHostingArbiter.addListener(listener)

        InAppHostingArbiter.release()
        assertFalse(InAppHostingArbiter.isInAppHosting)
        assertEquals(listOf(false), seen)

        // 幂等：重复 release 不再通知
        InAppHostingArbiter.release()
        assertEquals(listOf(false), seen)

        InAppHostingArbiter.removeListener(listener)
    }

    @Test
    fun `往返 - 交替通知且顺序稳定`() {
        InAppHostingArbiter.resetForTest()
        val seen = mutableListOf<Boolean>()
        val listener = InAppHostingArbiter.Listener { seen.add(it) }
        InAppHostingArbiter.addListener(listener)

        InAppHostingArbiter.claim()
        InAppHostingArbiter.release()
        InAppHostingArbiter.claim()
        assertEquals(listOf(true, false, true), seen)

        InAppHostingArbiter.removeListener(listener)
        InAppHostingArbiter.resetForTest()
    }

    @Test
    fun `注销后 - 不再收通知`() {
        InAppHostingArbiter.resetForTest()
        val seen = mutableListOf<Boolean>()
        val listener = InAppHostingArbiter.Listener { seen.add(it) }
        InAppHostingArbiter.addListener(listener)
        InAppHostingArbiter.removeListener(listener)

        InAppHostingArbiter.claim()
        assertTrue(seen.isEmpty())
        InAppHostingArbiter.release()
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `回调内自注销 - 快照遍历不抛 CME`() {
        InAppHostingArbiter.resetForTest()
        val other = mutableListOf<Boolean>()
        val lateListener = InAppHostingArbiter.Listener { }
        val selfRemoving = object : InAppHostingArbiter.Listener {
            override fun onInAppHostingChanged(active: Boolean) {
                InAppHostingArbiter.removeListener(this)
                InAppHostingArbiter.removeListener(lateListener)
            }
        }
        val stable = InAppHostingArbiter.Listener { other.add(it) }
        InAppHostingArbiter.addListener(selfRemoving)
        InAppHostingArbiter.addListener(lateListener)
        InAppHostingArbiter.addListener(stable)

        // 自注销 + 同批注销他人：本轮已快照的仍会收到，不抛异常
        InAppHostingArbiter.claim()
        assertTrue(other.contains(true))
        InAppHostingArbiter.release()
        assertTrue(other.contains(false))

        InAppHostingArbiter.removeListener(stable)
        InAppHostingArbiter.resetForTest()
    }

    @Test
    fun `回调抛异常 - 不向其他监听者传播`() {
        InAppHostingArbiter.resetForTest()
        val seen = mutableListOf<Boolean>()
        val explosive = InAppHostingArbiter.Listener { throw IllegalStateException("listener bug") }
        val stable = InAppHostingArbiter.Listener { seen.add(it) }
        InAppHostingArbiter.addListener(explosive)
        InAppHostingArbiter.addListener(stable)

        InAppHostingArbiter.claim()
        // 炸的没拖垮别人，状态也照常迁移
        assertTrue(InAppHostingArbiter.isInAppHosting)
        assertEquals(listOf(true), seen)

        InAppHostingArbiter.removeListener(explosive)
        InAppHostingArbiter.removeListener(stable)
        InAppHostingArbiter.resetForTest()
    }

    @Test
    fun `claim-release 与 isInAppHosting 读数始终一致`() {
        InAppHostingArbiter.resetForTest()
        assertEquals(false, InAppHostingArbiter.isInAppHosting)
        InAppHostingArbiter.claim()
        assertEquals(true, InAppHostingArbiter.isInAppHosting)
        InAppHostingArbiter.release()
        assertEquals(false, InAppHostingArbiter.isInAppHosting)
    }
}
