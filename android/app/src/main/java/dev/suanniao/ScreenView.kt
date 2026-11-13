package dev.suanniao

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 原有网格中的「电脑屏幕」模块。
 *
 * 这里只负责画面承载、等比适配、缩放/平移和触控坐标映射。
 * 画面连接、解码与权限认证由后续的独立视频通道负责；本类不建立网络连接。
 */
class ScreenView(context: Context) : FrameLayout(context) {
    enum class PointerAction { DOWN, MOVE, UP }
    enum class InputMode { VIEW, DIRECT, PRECISE }
    enum class CursorStyle { ARROW, CROSSHAIR }

    /** 只把电脑画面中有效的像素坐标交给已有控制层。 */
    var onRemotePointer: ((PointerAction, Int, Int) -> Unit)? = null
    var onRemoteRightClick: (() -> Unit)? = null
    var onRemoteScroll: ((Int) -> Unit)? = null
    var onMouseHoldChanged: ((Boolean) -> Unit)? = null
    var onQuickActionsRequested: (() -> Unit)? = null
    var onAppSwitchRequested: (() -> Unit)? = null
    var onSelectionHint: ((String) -> Unit)? = null
    var onSelectionStateChanged: ((Boolean) -> Unit)? = null
    var onExpandRequested: (() -> Unit)? = null
    var onSurfaceAvailable: ((SurfaceTexture) -> Unit)? = null
    var onSurfaceDestroyed: (() -> Unit)? = null
    /** 桌面尺寸变了（第一帧、电脑换分辨率）：全屏竖屏时 MainActivity 据此把画面调成正好的高度，贴在最上面 */
    var onDesktopSizeChanged: ((Int, Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val cursorPrefs = context.getSharedPreferences("screen_workbench_ui", Context.MODE_PRIVATE)
    // Each SurfaceTexture has exactly one native producer. MediaCodec uses
    // texture (API_MEDIA), while JPEG Canvas uses jpegTexture (API_CPU).
    // Reusing one SurfaceTexture for both causes MIUI/MTK
    // BufferQueueProducer 'already connected (cur=2 req=3)' on reconnect.
    private val texture = TextureView(context)
    private val jpegTexture = TextureView(context)
    private val status = TextView(context)
    private val reconnectHint = TextView(context)
    private val expand = TextView(context)
    private var sourceWidth = 0
    private var sourceHeight = 0
    /** 最近一次知道的桌面尺寸：断线时 sourceWidth 清成 0，这个还留着（手机「调整布局」按画面比例定高、侧栏算右边空白用） */
    private var knownWidth = 0
    private var knownHeight = 0
    private var decoderBufferWidth = 0
    private var decoderBufferHeight = 0
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var displayMode = ScreenCoordinates.DisplayMode.CONTAIN
    private var inputMode = InputMode.DIRECT
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorInitialized = false
    var cursorStyle: CursorStyle = if (cursorPrefs.getString("cursor_style", "arrow") == "crosshair")
        CursorStyle.CROSSHAIR else CursorStyle.ARROW
        set(value) {
            field = value
            cursorPrefs.edit().putString("cursor_style",
                if (value == CursorStyle.ARROW) "arrow" else "crosshair").apply()
            invalidate()
        }
    var cursorSize: Float = cursorPrefs.getFloat("cursor_size", 0.85f).coerceIn(0.75f, 1.3f)
        set(value) {
            field = value.coerceIn(0.75f, 1.3f)
            cursorPrefs.edit().putFloat("cursor_size", field).apply()
            invalidate()
        }
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var pinching = false
    private var dragging = false
    private var remoteHeld = false
    private var lastRemoteX = 0
    private var lastRemoteY = 0
    private var downX = 0f
    private var downY = 0f
    private var lastPinchSpan = 0f
    private var lastPinchX = 0f
    private var lastPinchY = 0f
    private var lastTwoFingerY = Float.NaN
    private var accumulatedScrollY = 0f
    private var gestureDownAt = 0L
    private var directScrolling = false
    private var directLongDrag = false
    private var directMoved = false
    private var selectionDragPending = false
    private var selectionCornerStarted = false
    private val selectionTimeout = Runnable {
        if (selectionCornerStarted) {
            cancelRemoteGesture()
            onSelectionHint?.invoke("截图选区已取消，未完成的鼠标按住已释放")
        }
    }
    private var multiFingerCount = 0
    private var multiTapMoved = false
    private var multiStartSpan = 0f
    private var multiStartMidX = 0f
    private var multiStartMidY = 0f
    private var multiLastMidY = 0f
    private var twoFingerIntent = RemoteTouchRules.TwoFingerIntent.UNDECIDED
    private val longDragStart = Runnable {
        if (inputMode == InputMode.DIRECT && !pinching && !directMoved &&
            !remoteHeld && sourceWidth > 0 && mapped(downX, downY) != null) {
            directLongDrag = true
            send(PointerAction.DOWN, downX, downY)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    val surfaceTexture: SurfaceTexture?
        get() = if (texture.isAvailable) texture.surfaceTexture else null

    fun hasDecoderSurface(): Boolean = texture.isAvailable && texture.surfaceTexture != null

    /** Called on the UI thread before MediaCodec starts rendering to this TextureView. */
    fun createDecoderSurface(
        desktopWidth: Int, desktopHeight: Int,
        encodedWidth: Int, encodedHeight: Int,
    ): Surface? {
        if (!texture.isAvailable || desktopWidth <= 0 || desktopHeight <= 0 ||
            encodedWidth <= 0 || encodedHeight <= 0) return null
        val surfaceTexture = texture.surfaceTexture ?: return null
        // Screen coordinates always use the *physical desktop* dimensions.
        setDesktopSize(desktopWidth, desktopHeight)
        // The SurfaceTexture consumer must match the actual H264 coded frame.
        // Using desktop 2560x1440 for encoded 1920x1080 previously left a
        // large unpainted region at the bottom/right of the phone display.
        surfaceTexture.setDefaultBufferSize(encodedWidth, encodedHeight)
        decoderBufferWidth = encodedWidth
        decoderBufferHeight = encodedHeight
        updateTransform()
        return Surface(surfaceTexture)
    }

    init {
        setBackgroundColor(Ui.palette(context).bg)
        clipChildren = true
        clipToPadding = true
        jpegTexture.isOpaque = true
        texture.isOpaque = true
        // Separate producers, identical geometry and hit area.
        addView(jpegTexture, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        addView(texture, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        // Keep both TextureViews attached so the H264 Surface is always ready.
        texture.alpha = 0f
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                decoderBufferWidth = 0
                decoderBufferHeight = 0
                updateTransform()
                onSurfaceAvailable?.invoke(surface)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                updateTransform()
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                decoderBufferWidth = 0
                decoderBufferHeight = 0
                onSurfaceDestroyed?.invoke()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        texture.setOnTouchListener { _, event -> handleTouch(event) }
        jpegTexture.setOnTouchListener { _, event -> handleTouch(event) }

        status.apply {
            text = "等待电脑画面\n请连接同一局域网"
            setTextColor(Ui.palette(context).sub)
            textSize = 12f
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.alpha(Ui.palette(context).tile, .88f))
        }
        addView(status, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        reconnectHint.apply {
            text = "画面重连中…"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Ui.palette(context).fg)
            setPadding((12 * density).toInt(), (6 * density).toInt(),
                (12 * density).toInt(), (6 * density).toInt())
            background = Ui.round(Ui.alpha(Ui.palette(context).tile, .86f),
                12f * density, 0, Ui.palette(context).tile)
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        addView(reconnectHint, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (8 * density).toInt()
            })

        expand.apply {
            text = "⛶"
            contentDescription = "放大电脑屏幕"
            textSize = 22f
            setTextColor(Ui.palette(context).fg)
            gravity = Gravity.CENTER
            background = Ui.round(Ui.palette(context).tile, 12f * density, Ui.hairline(context).toInt(), Ui.palette(context).line)
            setOnClickListener { onExpandRequested?.invoke() }
        }
        addView(expand, LayoutParams((42 * density).roundToInt(), (38 * density).roundToInt(), Gravity.TOP or Gravity.END))
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateTransform() }
    }

    /** 由视频解码层在收到桌面实际尺寸后调用；没有画面时宽高设为 0。 */
    fun setDesktopSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            sourceWidth = 0
            sourceHeight = 0
            zoom = 1f
            panX = 0f
            panY = 0f
            status.visibility = View.VISIBLE
            reconnectHint.visibility = View.GONE
            return
        }
        knownWidth = width
        knownHeight = height
        if (width != sourceWidth || height != sourceHeight) {
            sourceWidth = width
            sourceHeight = height
            cursorX = width / 2f
            cursorY = height / 2f
            cursorInitialized = true
            resetZoom()
            onDesktopSizeChanged?.invoke(width, height)
        }
        status.visibility = View.GONE
        reconnectHint.visibility = View.GONE
    }

    fun desktopSize(): Pair<Int, Int> = sourceWidth to sourceHeight

    /** 见过的桌面尺寸（现在没画面也算），从没见过是 0 to 0 */
    fun knownDesktopSize(): Pair<Int, Int> = knownWidth to knownHeight

    /**
     * 不放大时画面的右边（像素，本控件里的坐标）：全屏横屏的侧栏塞进它右边的空白（v3.3 U1，画面不再给侧栏让位）。
     * 铺满屏幕时画面占满整个宽，返回控件的宽；从没见过桌面尺寸返回 0（当成右边全是空白）。断线时用最近一次的尺寸，侧栏不跟着跳
     */
    fun fitPictureRight(): Int {
        val w = if (sourceWidth > 0) sourceWidth else knownWidth
        val h = if (sourceHeight > 0) sourceHeight else knownHeight
        val g = ScreenCoordinates.geometry(w, h, texture.width, texture.height, 1f, 0f, 0f, displayMode) ?: return 0
        return (g.left + g.imageWidth).roundToInt().coerceIn(0, texture.width)
    }

    /** 画面能用的宽（侧栏不再让位，就是整个宽）；还没量出大小是 0 */
    private fun viewportWidth(): Int = texture.width.coerceAtLeast(0)

    /** JPEG must never lock the SurfaceTexture owned by MediaCodec. */
    fun renderFrame(bitmap: Bitmap, width: Int, height: Int) {
        try {
            if (!jpegTexture.isAvailable) return
            setDesktopSize(width, height)
            // JPEG and H264 use separate SurfaceTexture producer connections.
            // The same transform and screen coordinates apply to both layers.
            val canvas = jpegTexture.lockCanvas() ?: return
            try {
                canvas.drawColor(0xff000000.toInt())
                canvas.drawBitmap(bitmap, null,
                    Rect(0, 0, jpegTexture.width, jpegTexture.height),
                    Paint(Paint.FILTER_BITMAP_FLAG))
            } finally {
                jpegTexture.unlockCanvasAndPost(canvas)
            }
            // Do not switch until the first JPEG frame is actually painted.
            jpegTexture.alpha = 1f
            texture.alpha = 0f
        } catch (_: IllegalArgumentException) {
            // Surface may have been destroyed during rotation; next frame retries.
        } finally { bitmap.recycle() }
    }

    /** Called only once the H264 decoder has produced actual output frames. */
    fun showH264Frames() {
        if (texture.isAvailable) {
            texture.alpha = 1f
            jpegTexture.alpha = 0f
            reconnectHint.visibility = View.GONE
        }
    }

    fun setExpanded(expanded: Boolean) {
        expand.visibility = if (expanded) View.GONE else View.VISIBLE
        expand.contentDescription = if (expanded) "返回蒜鸟布局" else "放大电脑屏幕"
        // Entering the remote workbench should feel like touching a tablet:
        // one finger clicks or drags the PC immediately, without a mode button.
        // VIEW/PRECISE remain optional tools for zoom/pan or tiny targets.
        setInputMode(InputMode.DIRECT)
        resetZoom()
    }

    fun setDisplayMode(mode: ScreenCoordinates.DisplayMode) {
        if (displayMode == mode) return
        displayMode = mode
        resetZoom()
    }

    fun getDisplayMode() = displayMode
    fun getInputMode() = inputMode

    fun setInputMode(mode: InputMode) {
        if (inputMode == mode) return
        cancelRemoteGesture()
        inputMode = mode
        if (mode == InputMode.PRECISE && !cursorInitialized && sourceWidth > 0) {
            cursorX = sourceWidth / 2f
            cursorY = sourceHeight / 2f
            cursorInitialized = true
        }
        invalidate()
    }

    /** Windows snipping mode needs the next swipe to select a rectangle, not scroll. */
    fun armNextSelectionDrag() {
        cancelRemoteGesture()
        // Screenshot selection is a transient gesture override. Do NOT switch
        // the persistent mode to DIRECT: that desynchronizes the floating
        // precision mouse/keyboard controls and forces users back into a rail.
        selectionDragPending = true
        selectionCornerStarted = false
        onSelectionStateChanged?.invoke(true)
        onSelectionHint?.invoke("截图：单指拖出选区，或依次点选左上角和右下角")
    }

    fun cancelSelectionDrag() {
        if (selectionDragPending) {
            cancelRemoteGesture()
            onSelectionHint?.invoke("已取消截图选区并释放鼠标")
        }
    }

    fun isMouseHeld() = remoteHeld

    /** 明确锁住/释放远端左键，供截图框选及文件拖放使用。 */
    fun toggleMouseHold(): Boolean {
        if (inputMode != InputMode.PRECISE || sourceWidth <= 0) return false
        if (remoteHeld) {
            sendCursor(PointerAction.UP)
        } else {
            sendCursor(PointerAction.DOWN)
        }
        return remoteHeld
    }

    /** A single click without entering persistent left-button drag mode. */
    fun leftClickAtCursor() {
        if (inputMode == InputMode.PRECISE && sourceWidth > 0 && !remoteHeld) {
            sendCursor(PointerAction.DOWN)
            sendCursor(PointerAction.UP)
        }
    }

    fun rightClickAtCursor() {
        if (inputMode == InputMode.PRECISE && sourceWidth > 0 && !remoteHeld) {
            sendCursor(PointerAction.MOVE)
            onRemoteRightClick?.invoke()
        }
    }

    fun scrollAtCursor(direction: Int) {
        if (inputMode == InputMode.PRECISE && sourceWidth > 0 && !remoteHeld)
            onRemoteScroll?.invoke(direction)
    }

    private fun sendCursor(action: PointerAction) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        val x = cursorX.toInt().coerceIn(0, sourceWidth - 1)
        val y = cursorY.toInt().coerceIn(0, sourceHeight - 1)
        if (action == PointerAction.DOWN) remoteHeld = true
        onRemotePointer?.invoke(action, x, y)
        lastRemoteX = x
        lastRemoteY = y
        if (action == PointerAction.UP) remoteHeld = false
        onMouseHoldChanged?.invoke(remoteHeld)
    }

    /** Keep the last frame visible during short video transport reconnects. */
    fun showReconnecting() {
        cancelRemoteGesture()
        if (sourceWidth > 0 && sourceHeight > 0) {
            reconnectHint.visibility = View.VISIBLE
        } else {
            status.text = "等待画面连接…"
            status.visibility = View.VISIBLE
        }
    }

    fun showDisconnected(message: String = "等待电脑画面\n请连接同一局域网") {
        cancelRemoteGesture()
        setDesktopSize(0, 0)
        status.text = message
    }

    fun resetZoom() {
        zoom = 1f
        panX = 0f
        panY = 0f
        updateTransform()
    }

    private fun fitScale(): Float =
        ScreenCoordinates.geometry(sourceWidth, sourceHeight, viewportWidth(), texture.height,
            1f, 0f, 0f, displayMode)?.scale ?: 0f

    private fun mapped(x: Float, y: Float, clampToScreen: Boolean = false): Pair<Int, Int>? =
        ScreenCoordinates.toDesktop(
            sourceWidth, sourceHeight, viewportWidth(), texture.height,
            zoom, panX, panY, x, y, clampToScreen, displayMode,
        )

    private fun clampPan() {
        val fit = fitScale()
        val maxX = max(0f, (sourceWidth * fit * zoom - viewportWidth()) / 2f)
        val maxY = max(0f, (sourceHeight * fit * zoom - texture.height) / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    private fun updateTransform() {
        val fit = fitScale()
        if (fit <= 0f) return
        clampPan()
        // Canvas 先绘制原帧到 TextureView，矩阵将它还原为等比铺满/完整显示。
        // 唯一几何源同样用于远端鼠标坐标和精密准星，避免画面与点击错位。
        // 缩放比例按 TextureView 真实的宽高（帧先铺满整个 TextureView）
        val geometry = ScreenCoordinates.geometry(
            sourceWidth, sourceHeight, viewportWidth(), texture.height, zoom, panX, panY, displayMode
        ) ?: return
        val matrix = Matrix()
        matrix.setScale(geometry.imageWidth / texture.width, geometry.imageHeight / texture.height)
        matrix.postTranslate(geometry.left, geometry.top)
        texture.setTransform(matrix)
        if (jpegTexture.isAvailable) jpegTexture.setTransform(matrix)
        invalidate()
    }

    private fun pinch(event: MotionEvent) {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        val span = hypot(dx, dy)
        val midX = (event.getX(0) + event.getX(1)) / 2f
        val midY = (event.getY(0) + event.getY(1)) / 2f
        if (lastPinchSpan > 0f) {
            val fit = fitScale()
            if (fit > 0f) {
                val oldFactor = fit * zoom
                val anchorX = (lastPinchX - (viewportWidth() - sourceWidth * oldFactor) / 2f - panX) / oldFactor
                val anchorY = (lastPinchY - (texture.height - sourceHeight * oldFactor) / 2f - panY) / oldFactor
                zoom = (zoom * span / lastPinchSpan).coerceIn(1f, 5f)
                val newFactor = fit * zoom
                panX = midX - (viewportWidth() - sourceWidth * newFactor) / 2f - anchorX * newFactor
                panY = midY - (texture.height - sourceHeight * newFactor) / 2f - anchorY * newFactor
                updateTransform()
            }
        }
        lastPinchSpan = span
        lastPinchX = midX
        lastPinchY = midY
    }

    private fun send(action: PointerAction, x: Float, y: Float) {
        if (action != PointerAction.DOWN && !remoteHeld) return
        val p = mapped(x, y, clampToScreen = action != PointerAction.DOWN) ?: return
        val sink = onRemotePointer ?: return
        if (action == PointerAction.DOWN) remoteHeld = true
        lastRemoteX = p.first
        lastRemoteY = p.second
        sink(action, p.first, p.second)
        if (action == PointerAction.UP) remoteHeld = false
    }

    /**
     * 电脑那边已经全部松开了（电脑发来 keysReleased：改配置、暂停、断线……）：只把这边记的「左键按着」、
     * 截图框选到一半清掉，不再发抬起（电脑上本来就松了）。
     */
    fun forgetRemoteHold() {
        if (!remoteHeld) return
        // 手指还按着、正在长按拖动也不改别的状态：之后的移动、抬起看到没按着就不发，也不会被当成一次点击
        remoteHeld = false
        if (selectionCornerStarted) {
            // 框选点了起点、在等第二下：左键已经松了，这次框选作废
            texture.removeCallbacks(selectionTimeout)
            selectionDragPending = false
            selectionCornerStarted = false
            onSelectionStateChanged?.invoke(false)
        }
        onMouseHoldChanged?.invoke(false)
        invalidate() // 精密鼠标的箭头按着时是橙色，变回白色
    }

    /** 页面离开、放大层重挂载、断线时释放左键，避免电脑卡在拖动状态。 */
    fun cancelRemoteGesture() {
        texture.removeCallbacks(longDragStart)
        texture.removeCallbacks(selectionTimeout)
        directLongDrag = false
        directScrolling = false
        selectionDragPending = false
        selectionCornerStarted = false
        onSelectionStateChanged?.invoke(false)
        if (remoteHeld) {
            remoteHeld = false
            onRemotePointer?.invoke(PointerAction.UP, lastRemoteX, lastRemoteY)
            onMouseHoldChanged?.invoke(false)
        }
        dragging = false
        pinching = false
        lastPinchSpan = 0f
    }

    override fun onDetachedFromWindow() {
        cancelRemoteGesture()
        super.onDetachedFromWindow()
    }

    private fun hoverDesktop(x: Float, y: Float) {
        val point = mapped(x, y) ?: return
        onRemotePointer?.invoke(PointerAction.MOVE, point.first, point.second)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 精密模式可以跨多次触摸保持鼠标左键；其它模式只允许完整一次手势。
                if (inputMode != InputMode.PRECISE || selectionDragPending) {
                    // During temporary selection, keep the remote left button
                    // held between two taps even if precision mode is active.

                    if (!(selectionDragPending && selectionCornerStarted)) {
                        val wasSelectionArmed = selectionDragPending
                        cancelRemoteGesture()
                        selectionDragPending = wasSelectionArmed
                    }
                }
                pinching = false
                dragging = false
                directMoved = false
                directScrolling = false
                directLongDrag = false
                multiFingerCount = 0
                multiTapMoved = false
                accumulatedScrollY = 0f
                gestureDownAt = event.eventTime
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                if (inputMode == InputMode.DIRECT && !selectionDragPending) {
                    texture.removeCallbacks(longDragStart)
                    texture.postDelayed(longDragStart, RemoteTouchRules.DRAG_HOLD_MS)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 双指手势优先用于本地画面缩放，绝不能让远端继续卡在按住左键状态。
                texture.removeCallbacks(longDragStart)
                if (remoteHeld) cancelRemoteGesture()
                dragging = false
                pinching = true
                multiFingerCount = maxOf(multiFingerCount, event.pointerCount)
                multiTapMoved = false
                twoFingerIntent = RemoteTouchRules.TwoFingerIntent.UNDECIDED
                lastPinchSpan = 0f
                accumulatedScrollY = 0f
                if (event.pointerCount >= 2) {
                    multiStartMidX = (event.getX(0) + event.getX(1)) / 2f
                    multiStartMidY = (event.getY(0) + event.getY(1)) / 2f
                    multiLastMidY = multiStartMidY
                    multiStartSpan = hypot(event.getX(0) - event.getX(1),
                        event.getY(0) - event.getY(1))
                    lastTwoFingerY = multiStartMidY
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    if (remoteHeld) cancelRemoteGesture()
                    pinching = true
                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    if (multiFingerCount == 3 || event.pointerCount >= 3) {
                        if (hypot(midX - multiStartMidX, midY - multiStartMidY) > 9f * density)
                            multiTapMoved = true
                    } else if (inputMode == InputMode.DIRECT) {
                        val span = hypot(event.getX(0) - event.getX(1),
                            event.getY(0) - event.getY(1))
                        if (twoFingerIntent == RemoteTouchRules.TwoFingerIntent.UNDECIDED) {
                            twoFingerIntent = RemoteTouchRules.classifyTwoFingers(
                                multiStartSpan, span, midX - multiStartMidX,
                                midY - multiStartMidY, density)
                            if (twoFingerIntent != RemoteTouchRules.TwoFingerIntent.UNDECIDED)
                                multiTapMoved = true
                        }
                        when (twoFingerIntent) {
                            RemoteTouchRules.TwoFingerIntent.ZOOM -> pinch(event)
                            RemoteTouchRules.TwoFingerIntent.SCROLL -> {
                                hoverDesktop(midX, midY)
                                accumulatedScrollY += midY - multiLastMidY
                                val step = 28f * density
                                while (accumulatedScrollY >= step) {
                                    onRemoteScroll?.invoke(-1)
                                    accumulatedScrollY -= step
                                }
                                while (accumulatedScrollY <= -step) {
                                    onRemoteScroll?.invoke(1)
                                    accumulatedScrollY += step
                                }
                            }
                            else -> Unit
                        }
                    } else if (inputMode == InputMode.PRECISE &&
                        RemoteTouchRules.preciseWheelAllowed(event.pointerCount, remoteHeld)) {
                        accumulatedScrollY += midY - multiLastMidY
                        val step = 28f * density
                        while (accumulatedScrollY >= step) {
                            onRemoteScroll?.invoke(-1)
                            accumulatedScrollY -= step
                        }
                        while (accumulatedScrollY <= -step) {
                            onRemoteScroll?.invoke(1)
                            accumulatedScrollY += step
                        }
                    } else pinch(event)
                    multiLastMidY = midY
                    lastTwoFingerY = midY
                } else if (!pinching && sourceWidth > 0) {
                    when (if (selectionDragPending) InputMode.DIRECT else inputMode) {
                        InputMode.VIEW -> {
                            // 浏览画面时单指只平移视野，不产生 Windows 鼠标拖动。
                            panX += event.x - lastTouchX
                            panY += event.y - lastTouchY
                            updateTransform()
                        }
                        InputMode.DIRECT -> {
                            if (selectionDragPending && !directLongDrag &&
                                hypot(event.x - downX, event.y - downY) > 8f * density) {
                                directLongDrag = true
                                if (!selectionCornerStarted) send(PointerAction.DOWN, downX, downY)
                            }
                            if (directLongDrag) {
                                send(PointerAction.MOVE, event.x, event.y)
                            } else {
                                if (hypot(event.x - downX, event.y - downY) > 8f * density) {
                                    directMoved = true
                                    directScrolling = true
                                    texture.removeCallbacks(longDragStart)
                                }
                                if (directScrolling) {
                                    hoverDesktop(event.x, event.y)
                                    accumulatedScrollY += event.y - lastTouchY
                                    val step = 28f * density
                                    while (accumulatedScrollY >= step) {
                                        onRemoteScroll?.invoke(-1)
                                        accumulatedScrollY -= step
                                    }
                                    while (accumulatedScrollY <= -step) {
                                        onRemoteScroll?.invoke(1)
                                        accumulatedScrollY += step
                                    }
                                }
                            }
                        }
                        InputMode.PRECISE -> {
                            // Single finger moves pointer only. Two fingers scroll.
                            val g = ScreenCoordinates.geometry(
                                sourceWidth, sourceHeight, viewportWidth(), texture.height,
                                zoom, panX, panY, displayMode,
                            )
                            if (g != null) {
                                cursorX = (cursorX + (event.x - lastTouchX) / g.scale)
                                    .coerceIn(0f, (sourceWidth - 1).toFloat())
                                cursorY = (cursorY + (event.y - lastTouchY) / g.scale)
                                    .coerceIn(0f, (sourceHeight - 1).toFloat())
                                sendCursor(PointerAction.MOVE)
                                invalidate()
                            }
                            if (hypot(event.x - downX, event.y - downY) > 7f * density)
                                dragging = true
                        }
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                texture.removeCallbacks(longDragStart)
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    cancelRemoteGesture()
                } else if (!pinching) {
                    when (if (selectionDragPending) InputMode.DIRECT else inputMode) {
                        InputMode.VIEW -> Unit
                        InputMode.DIRECT -> {
                            if (selectionDragPending) {
                                if (directLongDrag || selectionCornerStarted) {
                                    // Single-handed two-corner capture ends on
                                    // the second tap; continuous drag also works.
                                    texture.removeCallbacks(selectionTimeout)
                                    send(PointerAction.MOVE, event.x, event.y)
                                    send(PointerAction.UP, event.x, event.y)
                                    selectionDragPending = false
                                    selectionCornerStarted = false
                                    onSelectionStateChanged?.invoke(false)
                                    onSelectionHint?.invoke("截图选区已完成，可用 Ctrl+V 粘贴到电脑")
                                } else if (!directScrolling && !directMoved) {
                                    // First short tap: hold Windows left mouse
                                    // button until the second tap selects end.
                                    send(PointerAction.DOWN, event.x, event.y)
                                    if (remoteHeld) {
                                        selectionCornerStarted = true
                                        texture.removeCallbacks(selectionTimeout)
                                        texture.postDelayed(selectionTimeout, 8_000L)
                                        onSelectionHint?.invoke("已选起点：再轻点选区的另一角")
                                    } else {
                                        selectionDragPending = false
                                        onSelectionHint?.invoke("截图选区不可用：请确认电脑画面已连接")
                                    }
                                }
                            } else if (directLongDrag) {
                                send(PointerAction.UP, event.x, event.y)
                            } else if (!directScrolling && !directMoved) {
                                send(PointerAction.DOWN, event.x, event.y)
                                send(PointerAction.UP, event.x, event.y)
                                performClick()
                            }
                        }
                        InputMode.PRECISE -> {
                            if (!dragging && !remoteHeld) {
                                sendCursor(PointerAction.DOWN)
                                sendCursor(PointerAction.UP)
                                performClick()
                            }
                        }
                    }
                }
                dragging = false
                pinching = false
                directScrolling = false
                directLongDrag = false
                lastPinchSpan = 0f
                lastTwoFingerY = Float.NaN
                accumulatedScrollY = 0f
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2 && multiFingerCount == 2 &&
                    inputMode == InputMode.DIRECT) {
                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    val elapsed = event.eventTime - gestureDownAt
                    when {
                        // Two-finger alternatives for Redmi/MIUI, whose OS
                        // screenshot recognizer consumes three-finger gestures.
                        RemoteTouchRules.isTwoFingerAppSwitch(
                            midX - multiStartMidX, midY - multiStartMidY, density) ->
                            onAppSwitchRequested?.invoke()
                        RemoteTouchRules.isTwoFingerShortcutHold(elapsed, multiTapMoved) ->
                            onQuickActionsRequested?.invoke()
                        RemoteTouchRules.isMultiTap(2, elapsed, multiTapMoved) -> {
                            hoverDesktop(midX, midY)
                            onRemoteRightClick?.invoke()
                        }
                    }
                    multiTapMoved = true
                } else if (event.pointerCount == 3 && multiFingerCount == 3) {
                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    if (RemoteTouchRules.isAppSwitchSwipe(
                            midX - multiStartMidX, midY - multiStartMidY, density)) {
                        onAppSwitchRequested?.invoke()
                    } else if (RemoteTouchRules.isMultiTap(
                            3, event.eventTime - gestureDownAt, multiTapMoved)) {
                        onQuickActionsRequested?.invoke()
                    }
                    multiTapMoved = true
                }
                return true
            }
        }
        return true
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (inputMode != InputMode.PRECISE || !cursorInitialized) return
        val g = ScreenCoordinates.geometry(
            sourceWidth, sourceHeight, viewportWidth(), texture.height,
            zoom, panX, panY, displayMode,
        ) ?: return
        val x = g.viewX(cursorX)
        val y = g.viewY(cursorY)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xEE101A23.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 4f * density
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (remoteHeld) 0xFFFFB454.toInt() else 0xFF53F2DC.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
        }
        if (cursorStyle == CursorStyle.ARROW) {
            val scale = density * cursorSize
            val arrow = Path().apply {
                moveTo(x, y)
                lineTo(x, y + 17f * scale)
                lineTo(x + 4.1f * scale, y + 12f * scale)
                lineTo(x + 8f * scale, y + 20f * scale)
                lineTo(x + 11f * scale, y + 18.3f * scale)
                lineTo(x + 7.2f * scale, y + 10f * scale)
                lineTo(x + 14.5f * scale, y + 10f * scale)
                close()
            }
            canvas.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (remoteHeld) 0xFFFFB454.toInt() else 0xFFFFFFFF.toInt()
                style = Paint.Style.FILL
            })
            canvas.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xEE101A23.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 1.4f * scale
            })
        } else {
            val arm = 9f * density * cursorSize
            val gap = 3f * density * cursorSize
            for (paint in listOf(outline, line)) {
                paint.strokeWidth = if (paint === outline) 2.5f * density else 1.2f * density
                canvas.drawLine(x - arm, y, x - gap, y, paint)
                canvas.drawLine(x + gap, y, x + arm, y, paint)
                canvas.drawLine(x, y - arm, x, y - gap, paint)
                canvas.drawLine(x, y + gap, x, y + arm, paint)
            }
            canvas.drawCircle(x, y, 1.5f * density, line)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/**
 * 「电脑屏幕」模块（网格里、全屏）局域网没连上、没画面时说什么：看局域网和蓝牙现在的情况说（v3.3 U4）。
 * 用户遇到过电脑自己的网反复掉，手机上只看到「等待局域网连接」，不知道是电脑没网、正在连还是连不上。
 */
internal object ScreenLinkHint {
    /**
     * bt：蓝牙连着；lanPhase：局域网那条连接的情况；pcOffline：电脑说它现在没有网（lan 消息里 addrs 是空的）；
     * bound：扫码绑定过局域网；revoked：电脑上解除了这部手机的绑定
     */
    fun text(bt: Boolean, lanPhase: LinkService.LanPhase, pcOffline: Boolean, bound: Boolean, revoked: Boolean): String = when {
        !bound -> "请扫码绑定局域网"
        revoked || lanPhase == LinkService.LanPhase.REVOKED -> "电脑上解除了绑定：重新扫码才能看画面"
        pcOffline && bt -> "电脑的网络断了，正在等它恢复（蓝牙还能控制）"
        lanPhase == LinkService.LanPhase.CONNECTING -> "正在重连局域网…"
        bt -> "局域网连不上，蓝牙还能控制"
        else -> "等待局域网连接"
    }
}
