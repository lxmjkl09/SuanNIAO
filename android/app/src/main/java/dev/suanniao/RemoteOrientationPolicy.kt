package dev.suanniao

import android.content.pm.ActivityInfo
import android.view.Surface

/** 锁定方向：手机现在怎么拿着就锁成什么样。横屏要分清左右，锁的时候不能转半圈（以前出过画面翻 180°） */
internal object RemoteOrientationPolicy {
    fun lockCurrent(displayRotation: Int): Int = when (displayRotation) {
        Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}
