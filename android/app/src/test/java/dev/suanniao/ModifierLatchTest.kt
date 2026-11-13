package dev.suanniao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModifierLatchTest {
    @Test fun cycleIsOffOnceLockedOff() {
        val latch = ModifierLatch()
        assertEquals(true, latch.cycle("ctrl"))     // 关 → 一次：发按下
        assertEquals(ModifierLatch.State.ONCE, latch.state("ctrl"))
        assertNull(latch.cycle("ctrl"))             // 一次 → 锁住：不用发
        assertEquals(ModifierLatch.State.LOCKED, latch.state("ctrl"))
        assertEquals(false, latch.cycle("ctrl"))    // 锁住 → 关：发松开
        assertNull(latch.cycle("hyper"))
    }

    @Test fun consumeReleasesOnlyOnce() {
        val latch = ModifierLatch()
        latch.cycle("ctrl")
        latch.cycle("shift"); latch.cycle("shift")
        assertEquals(listOf("ctrl"), latch.consumeOnce())
        assertEquals(ModifierLatch.State.LOCKED, latch.state("shift"))
        assertEquals(listOf("shift"), latch.releaseAll())
        assertFalse(latch.anyActive())
    }

    @Test fun dropRevertsAPressThatNeverReachedThePc() {
        val latch = ModifierLatch()
        assertEquals(true, latch.cycle("ctrl"))
        latch.drop("ctrl")
        assertEquals(ModifierLatch.State.OFF, latch.state("ctrl"))
        assertFalse(latch.anyActive())
        assertEquals(emptyList<String>(), latch.releaseAll())
        latch.drop("hyper") // 不认识的键：什么都不做
        assertEquals(true, latch.cycle("ctrl")) // 再点一下又是「一次」，重新发按下
    }

    @Test fun chipTextDescribesState() {
        val latch = ModifierLatch()
        assertNull(latch.chipText())
        latch.cycle("alt"); latch.cycle("alt")
        latch.cycle("ctrl")
        assertEquals("Alt 锁住中 · Ctrl 等下一次点击 / 按键", latch.chipText())
        assertTrue(latch.anyActive())
    }
}
