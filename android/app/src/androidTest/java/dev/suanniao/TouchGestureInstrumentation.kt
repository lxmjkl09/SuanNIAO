package dev.suanniao

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test-only Android UiAutomation gesture runner. It intercepts outgoing screen
 * controls so tests never click/scroll/type on the paired Windows computer.
 */
class TouchGestureInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        val gesture = arguments?.getString("gesture") ?: "threeTap"
        val route = arguments?.getString("route") ?: "system"
        start()
        Thread {
            try {
                val intent = targetContext.packageManager.getLaunchIntentForPackage("dev.suanniao.dev")
                    ?: error("Dev launch intent missing")
                val activity = startActivitySync(intent)
                SystemClock.sleep(1700)

                fun find(root: View, predicate: (View) -> Boolean): View? {
                    if (predicate(root)) return root
                    if (root is ViewGroup) {
                        for (index in 0 until root.childCount) {
                            val v = find(root.getChildAt(index), predicate)
                            if (v != null) return v
                        }
                    }
                    return null
                }
                fun findScreen() = find(activity.window.decorView) { it is ScreenView } as? ScreenView
                if (findScreen() == null) {
                    runOnMainSync {
                        find(activity.window.decorView) {
                            (it is TextView && it.text?.contains("远程屏幕测试") == true)
                        }?.performClick()
                    }
                    SystemClock.sleep(500)
                }
                runOnMainSync {
                    val expand = find(activity.window.decorView) {
                        it.contentDescription?.contains("放大电脑屏幕") == true
                    }
                    expand?.performClick()
                }
                SystemClock.sleep(1200)
                val screen = findScreen() ?: error("Remote ScreenView unavailable")
                check(screen.isShown) { "Remote ScreenView not shown" }
                if (gesture == "rotate") {
                    val originalOrientation = activity.requestedOrientation
                    val visibility = mutableListOf<String>()
                    try {
                        for (orientation in listOf(
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                        )) {
                            runOnMainSync { activity.requestedOrientation = orientation }
                            SystemClock.sleep(1900)
                            runOnMainSync {
                                val ball = find(activity.window.decorView) {
                                    it.contentDescription?.contains("远程快捷控制") == true
                                } ?: error("Floating control missing after rotation")
                                val rect = Rect()
                                val visible = ball.getGlobalVisibleRect(rect)
                                visibility.add("${orientation}:${visible}:${rect.toShortString()}")
                                check(visible && rect.width() > 0 && rect.height() > 0) {
                                    "Floating control off screen at rotation $orientation"
                                }
                            }
                        }
                    } finally {
                        runOnMainSync { activity.requestedOrientation = originalOrientation }
                    }
                    val result = Bundle().apply {
                        putString("gesture", "rotate")
                        putString("rotationChecks", visibility.joinToString("|"))
                    }
                    finish(0, result)
                    return@Thread
                }

                val quick = AtomicInteger()
                val switchApp = AtomicInteger()
                val right = AtomicInteger()
                val scroll = AtomicInteger()
                val pointer = AtomicInteger()
                val pointerSequence = java.util.Collections.synchronizedList(
                    mutableListOf<ScreenView.PointerAction>())
                runOnMainSync {
                    // Prevent production side effects on the paired PC.
                    screen.onRemotePointer = { action, _, _ ->
                        pointerSequence.add(action)
                        if (action == ScreenView.PointerAction.DOWN) pointer.incrementAndGet()
                    }
                    screen.onRemoteScroll = { scroll.addAndGet(it) }
                    screen.onRemoteRightClick = { right.incrementAndGet() }
                    screen.onQuickActionsRequested = { quick.incrementAndGet() }
                    screen.onAppSwitchRequested = { switchApp.incrementAndGet() }
                    // In disconnected mode the full-size waiting overlay covers
                    // TextureView. Force a fake desktop only inside this isolated
                    // test; all outgoing PC callbacks above are intercepted.
                    screen.setDesktopSize(2560, 1440)
                    screen.setInputMode(if (gesture == "screenTwoTapPrecise")
                        ScreenView.InputMode.PRECISE else ScreenView.InputMode.DIRECT)
                    if (gesture == "screenTwoTap" || gesture == "screenTwoTapPrecise") {
                        screen.armNextSelectionDrag()
                    }
                }

                // Use local screen view coordinates rather than absolute device size.
                val origin = IntArray(2)
                runOnMainSync { screen.getLocationOnScreen(origin) }
                val cx = origin[0] + screen.width * .48f
                val cy = origin[1] + screen.height * .45f
                val points = when (gesture) {
                    "oneTap", "screenTwoTap", "screenTwoTapPrecise" -> listOf(0f to 0f)
                    "twoTap", "twoScroll", "twoLong", "twoSwipe" -> listOf(-90f to 0f, 90f to 0f)
                    "threeTap", "threeSwipe" -> listOf(-135f to 0f, 0f to 0f, 135f to 0f)
                    else -> error("Unknown gesture $gesture")
                }
                val properties = points.indices.map { i ->
                    MotionEvent.PointerProperties().apply {
                        id = i
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                }
                var coords = points.map { offset ->
                    MotionEvent.PointerCoords().apply {
                        x = cx + offset.first
                        y = cy + offset.second
                        pressure = 1f
                        size = .05f
                    }
                }
                val startAt = SystemClock.uptimeMillis()
                val automation = uiAutomation
                fun inject(action: Int, active: Int) {
                    val e = MotionEvent.obtain(
                        startAt, SystemClock.uptimeMillis(), action, active,
                        properties.take(active).toTypedArray(), coords.take(active).toTypedArray(),
                        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
                    )
                    try {
                        if (route == "direct") {
                            runOnMainSync { screen.dispatchTouchEvent(e) }
                        } else check(automation.injectInputEvent(e, true)) { "Input injection rejected" }
                    } finally { e.recycle() }
                }
                inject(MotionEvent.ACTION_DOWN, 1)
                SystemClock.sleep(30)
                for (index in 1 until points.size) {
                    inject(MotionEvent.ACTION_POINTER_DOWN or
                        (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), index + 1)
                    SystemClock.sleep(30)
                }
                if (gesture == "twoLong") SystemClock.sleep(660)
                if (gesture == "threeSwipe" || gesture == "twoScroll" || gesture == "twoSwipe") {
                    for (step in 1..8) {
                        coords = points.map { offset ->
                            MotionEvent.PointerCoords().apply {
                                x = cx + offset.first + if (gesture == "threeSwipe" || gesture == "twoSwipe") step * 36f else 0f
                                y = cy + offset.second + if (gesture == "twoScroll") step * 23f else 0f
                                pressure = 1f
                                size = .05f
                            }
                        }
                        inject(MotionEvent.ACTION_MOVE, points.size)
                        SystemClock.sleep(40)
                    }
                }
                for (index in points.lastIndex downTo 1) {
                    inject(MotionEvent.ACTION_POINTER_UP or
                        (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), index + 1)
                    SystemClock.sleep(30)
                }
                inject(MotionEvent.ACTION_UP, 1)
                if (gesture == "screenTwoTap" || gesture == "screenTwoTapPrecise") {
                    // Two separate one-handed taps while the remote PC output
                    // is intercepted. First DOWN must stay held until second UP.
                    SystemClock.sleep(140)
                    coords = listOf(MotionEvent.PointerCoords().apply {
                        x = cx + 170f
                        y = cy + 130f
                        pressure = 1f
                        size = .05f
                    })
                    inject(MotionEvent.ACTION_DOWN, 1)
                    SystemClock.sleep(50)
                    inject(MotionEvent.ACTION_UP, 1)
                }
                SystemClock.sleep(300)

                val hit = when (gesture) {
                    "threeTap" -> quick.get() == 1
                    "threeSwipe" -> switchApp.get() == 1
                    "twoTap" -> right.get() == 1
                    "twoScroll" -> scroll.get() != 0
                    "twoLong" -> quick.get() == 1
                    "twoSwipe" -> switchApp.get() == 1
                    "oneTap" -> pointer.get() == 1
                    "screenTwoTap", "screenTwoTapPrecise" ->
                        pointerSequence.toList() == listOf(
                            ScreenView.PointerAction.DOWN,
                            ScreenView.PointerAction.MOVE,
                            ScreenView.PointerAction.UP) &&
                        (gesture != "screenTwoTapPrecise" ||
                            screen.getInputMode() == ScreenView.InputMode.PRECISE)
                    else -> false
                }
                val result = Bundle().apply {
                    putString("gesture", gesture)
                    putString("quick", quick.get().toString())
                    putString("switchApp", switchApp.get().toString())
                    putString("right", right.get().toString())
                    putString("scroll", scroll.get().toString())
                    putString("callbackVerified", hit.toString())
                    putString("route", route)
                    putString("screenDimensions", "${screen.width}x${screen.height}")
                    putString("screenOrigin", "${origin[0]},${origin[1]}")
                    putString("pointer", pointer.get().toString())
                    putString("pointerSequence", pointerSequence.joinToString(","))
                    putString("inputMode", screen.getInputMode().name)
                }
                sendStatus(if (hit) 0 else -1, result)
                finish(if (hit) 0 else -1, result)
            } catch (error: Throwable) {
                Log.e("SuanNIAO-gesture-test", "Failed gesture test", error)
                finish(-1, Bundle().apply { putString("error", error.toString()) })
            }
        }.start()
    }
}
