package dev.suanniao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTouchRulesTest {
    @Test fun precisionWheelRequiresExactlyTwoFingersAndNoHeldDrag() {
        assertFalse(RemoteTouchRules.preciseWheelAllowed(1, false))
        assertTrue(RemoteTouchRules.preciseWheelAllowed(2, false))
        assertFalse(RemoteTouchRules.preciseWheelAllowed(2, true))
        assertFalse(RemoteTouchRules.preciseWheelAllowed(3, false))
        assertFalse(RemoteTouchRules.preciseWheelAllowed(0, false))
    }

    @Test fun twoFingerParallelSwipeScrollsWithoutZooming() {
        assertEquals(RemoteTouchRules.TwoFingerIntent.SCROLL,
            RemoteTouchRules.classifyTwoFingers(200f, 203f, 2f, 35f, 1f))
    }
    @Test fun twoFingerSpreadZooms() {
        assertEquals(RemoteTouchRules.TwoFingerIntent.ZOOM,
            RemoteTouchRules.classifyTwoFingers(200f, 253f, 1f, 2f, 1f))
    }
    @Test fun smallJitterDoesNotAct() {
        assertEquals(RemoteTouchRules.TwoFingerIntent.UNDECIDED,
            RemoteTouchRules.classifyTwoFingers(200f, 203f, 1f, 2f, 1f))
    }
    @Test fun multiTouchTapMustBeShortAndStationary() {
        assertTrue(RemoteTouchRules.isMultiTap(2, 230L, false))
        assertTrue(RemoteTouchRules.isMultiTap(3, 230L, false))
        assertFalse(RemoteTouchRules.isMultiTap(2, 400L, false))
        assertFalse(RemoteTouchRules.isMultiTap(3, 200L, true))
    }
    @Test fun threeFingerSwipeSwitchesOnlyForHorizontalMotion() {
        assertTrue(RemoteTouchRules.isAppSwitchSwipe(90f, 12f, 1f))
        assertTrue(RemoteTouchRules.isAppSwitchSwipe(-90f, 10f, 1f))
        assertFalse(RemoteTouchRules.isAppSwitchSwipe(50f, 10f, 1f))
        assertFalse(RemoteTouchRules.isAppSwitchSwipe(80f, 95f, 1f))
    }

    @Test fun redmiTwoFingerAlternativesDoNotConflictWithScroll() {
        assertTrue(RemoteTouchRules.isTwoFingerAppSwitch(280f, 12f, 2.75f))
        assertFalse(RemoteTouchRules.isTwoFingerAppSwitch(20f, 280f, 2.75f))
        assertFalse(RemoteTouchRules.isTwoFingerAppSwitch(150f, 3f, 2.75f))
        assertTrue(RemoteTouchRules.isTwoFingerShortcutHold(650L, false))
        assertFalse(RemoteTouchRules.isTwoFingerShortcutHold(120L, false))
        assertFalse(RemoteTouchRules.isTwoFingerShortcutHold(680L, true))
    }

    @Test fun invalidSpanNeverTriggersZoomOrScroll() {
        assertEquals(RemoteTouchRules.TwoFingerIntent.UNDECIDED,
            RemoteTouchRules.classifyTwoFingers(0f, 0f, 200f, 0f, 1f))
    }
}
