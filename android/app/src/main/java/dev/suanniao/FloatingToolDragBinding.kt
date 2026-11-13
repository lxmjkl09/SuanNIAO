package dev.suanniao

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View

/** Attach only to the grip: mouse/voice action buttons keep their existing click behavior. */
@SuppressLint("ClickableViewAccessibility")
internal fun bindFloatingToolDrag(
    handle: View,
    host: View,
    threshold: Float,
    clamp: () -> Unit,
    save: () -> Unit,
) {
    var baseX = 0f
    var baseY = 0f
    var pointerId = MotionEvent.INVALID_POINTER_ID
    val drag = FloatingToolDrag(threshold) { capture ->
        handle.parent?.requestDisallowInterceptTouchEvent(capture)
    }
    handle.setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                baseX = host.translationX
                baseY = host.translationY
                drag.down(event.rawX, event.rawY)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                // Stop on pointer replacement instead of jumping to the next finger.
                if (event.getPointerId(0) != pointerId) {
                    drag.cancel()
                } else if (drag.move(event.rawX, event.rawY)) {
                    host.translationX = baseX + drag.dx
                    host.translationY = baseY + drag.dy
                    clamp()
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                when (drag.up()) {
                    FloatingToolDrag.End.DRAG -> save()
                    FloatingToolDrag.End.TAP -> view.performClick()
                    FloatingToolDrag.End.NONE -> Unit
                }
                pointerId = MotionEvent.INVALID_POINTER_ID
                true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                drag.cancel()
                pointerId = MotionEvent.INVALID_POINTER_ID
                true
            }
            else -> true
        }
    }
}
