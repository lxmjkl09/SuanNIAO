package dev.suanniao

import android.content.pm.ActivityInfo
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteOrientationPolicyTest {
    @Test fun landscapeKeepsTheSameSideWithoutHalfTurn() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, RemoteOrientationPolicy.lockCurrent(Surface.ROTATION_90))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, RemoteOrientationPolicy.lockCurrent(Surface.ROTATION_270))
    }

    @Test fun portraitCanBeLockedToo() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, RemoteOrientationPolicy.lockCurrent(Surface.ROTATION_0))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, RemoteOrientationPolicy.lockCurrent(Surface.ROTATION_180))
    }
}
