package dev.suanniao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapTriggersTest {
    @Test fun clickFiresOnReleaseWhenNoDoubleClick() {
        val t = TapTriggers(hasDouble = false, hasLong = false, doubleMs = 300, longMs = 500)
        t.down(0)
        assertEquals("click", t.up(80))
    }

    @Test fun doubleClickWithinWindow() {
        val t = TapTriggers(hasDouble = true, hasLong = false, doubleMs = 300, longMs = 500)
        t.down(0)
        assertNull(t.up(60))
        t.down(150)
        assertEquals("doubleClick", t.up(200))
        assertNull(t.tick(600))
    }

    @Test fun singleClickAfterDoubleWindowExpires() {
        val t = TapTriggers(hasDouble = true, hasLong = false, doubleMs = 300, longMs = 500)
        t.down(0)
        assertNull(t.up(60))
        assertNull(t.tick(200))
        assertEquals("click", t.tick(361))
    }

    @Test fun longPressSuppressesClick() {
        val t = TapTriggers(hasDouble = false, hasLong = true, doubleMs = 300, longMs = 500)
        t.down(0)
        assertNull(t.tick(400))
        assertEquals("longPress", t.tick(500))
        assertNull(t.up(700))
    }
}
