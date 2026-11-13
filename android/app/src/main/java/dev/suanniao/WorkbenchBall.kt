package dev.suanniao

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 悬浮球和它展开的那一圈按钮。点球开 / 关那一圈，双击球一起收起、展开语音条和键鼠条（v3.3 U6），
 * 拖球挪位置（松手吸到最近的一条边：左、右、上、下都行，v3.3 U3）。
 * 一圈按钮的位置见 BallRingLayout：朝屏幕里面开，整圈都在屏幕里；按钮少就排得少，不留空洞。点一圈外面关掉。
 * 球靠近屏幕边角时一圈放不下，圈的中心往屏幕里挪：这时球也跟着挪到圈中间（不然一圈会压住球），
 * 关掉再回到原来停的地方（rest）。开着时把球拖到别处，松手的地方就是新家。
 */
@SuppressLint("ClickableViewAccessibility")
internal class WorkbenchBall(context: Context) {
    private val d = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val palette = Ui.palette(context)
    val size = dp(50)

    val ball: TextView = AssistiveBall(context, palette).apply {
        gravity = Gravity.CENTER
        textSize = 19f
        typeface = Ui.font(context, 700)
        setTextColor(Ui.alpha(palette.fg, .75f))
        background = Ui.round(Ui.alpha(palette.tile, .91f), dp(25).toFloat(), Ui.hairline(context).toInt(), palette.line)
        elevation = dp(6).toFloat()
        contentDescription = "悬浮球：点一下打开常用按钮，双击一起收起或展开语音条、键鼠条，按住拖动"
    }
    val ring = FrameLayout(context).apply {
        visibility = View.GONE
        isClickable = true
        clipChildren = false
        clipToPadding = false
    }
    private var items: List<View> = emptyList()

    /** 球平时停的地方（左上角的 translation，像素）。一圈开着时球可能临时挪到圈中间，关掉回到这里 */
    var restX = 0f
        private set
    var restY = 0f
        private set
    /** 手指正拖着球：一圈跟着手指走，不去挪球 */
    private var dragging = false
    /** 松手后正往边上贴（230ms）：新家已经定了、也存了 */
    private var settling = false

    var onTap: (() -> Unit)? = null
    /** 双击球（v3.3 U6）；没设就没有双击，点一下马上算单击 */
    var onDoubleTap: (() -> Unit)? = null
    /** 拖完松手、球贴好边以后调；这时 restX / restY 就是新位置 */
    var onDragged: (() -> Unit)? = null
    val isOpen: Boolean get() = ring.visibility == View.VISIBLE

    /** 点了一下、还在等会不会有第二下（系统的双击时间）：等过了才算单击 */
    private var tapPending = false
    /** 这一按是双击的第二下 */
    private var secondTap = false
    private val singleTap = Runnable {
        tapPending = false
        // 等的时候退出了全屏：不再打开那一圈
        if (ball.isAttachedToWindow) ball.performClick()
    }

    init {
        ring.setOnClickListener { close() }
        // 读屏的「点按两下」也走这里，马上打开，不等双击
        ball.setOnClickListener {
            Ui.buzz(it, Ui.Buzz.TAP)
            onTap?.invoke()
        }
        var startX = 0f
        var startY = 0f
        var baseX = 0f
        var baseY = 0f
        ball.setOnTouchListener { _, e ->
            val parent = ball.parent as? View
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY
                    baseX = ball.translationX; baseY = ball.translationY
                    dragging = false
                    // 上一下还在等：这是第二下，上一下不再算单击
                    secondTap = tapPending
                    if (tapPending) {
                        ball.removeCallbacks(singleTap)
                        tapPending = false
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX
                    val dy = e.rawY - startY
                    if (!dragging && hypot(dx, dy) > dp(8)) {
                        dragging = true
                        settling = false
                        ball.animate().cancel() // 正在往圈中间挪、往回挪：停下，跟着手指走
                    }
                    if (dragging && parent != null) {
                        ball.translationX = (baseX + dx).coerceIn(dp(6).toFloat(), (parent.width - size - dp(6)).toFloat())
                        ball.translationY = (baseY + dy).coerceIn(dp(6).toFloat(), (parent.height - size - dp(6)).toFloat())
                        if (isOpen) layoutRing(animate = false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging && parent != null) {
                        dragging = false
                        // 松手的地方吸到最近的一条边（上下左右都行）就是新家：一圈开着也一样，关掉不回原来的位置
                        val (x, y) = BallRingLayout.snap(ball.translationX, ball.translationY,
                            parent.width.toFloat(), parent.height.toFloat(), size.toFloat(), dp(8).toFloat())
                        restX = x
                        restY = y
                        settling = true
                        ball.animate().translationX(restX).translationY(restY).setDuration(230).setInterpolator(Ui.SOFT_SPRING)
                            .withEndAction {
                                settling = false
                                if (isOpen) layoutRing(animate = true)
                            }.start()
                        // 松手就存，不等贴边动画走完：动画中途被打断（配置推来、尺寸变了）新位置也丢不了（审查 #16）
                        onDragged?.invoke()
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) tapped()
                    // 第二下按下去又拖走了：两下都不算点
                    secondTap = false
                }
            }
            true
        }
    }

    /**
     * 点了一下（没拖）：第二下就是双击；一圈开着就马上关（关不用等）；不然等过双击时间再算单击（v3.3 U6）
     */
    private fun tapped() {
        val double = onDoubleTap
        when {
            secondTap && double != null -> {
                Ui.buzz(ball, Ui.Buzz.TAP)
                double()
            }
            isOpen || double == null -> ball.performClick()
            else -> {
                tapPending = true
                ball.postDelayed(singleTap, ViewConfiguration.getDoubleTapTimeout().toLong())
            }
        }
    }

    fun attach(parent: FrameLayout) {
        parent.addView(ring, FrameLayout.LayoutParams(-1, -1))
        parent.addView(ball, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START))
    }

    /** 球停到 (x, y)（左上角，像素）。一圈开着时球照样待在圈中间，关掉才回到这里 */
    fun moveTo(x: Float, y: Float, animate: Boolean) {
        val parent = ball.parent as? View ?: return
        // 手指正拖着：手指说了算，松手时自己定新家。以前这里把 dragging 清掉，接着的松手被当成单击，开关了那一圈
        if (dragging) return
        val nx = x.coerceIn(dp(6).toFloat(), (parent.width - size - dp(6)).toFloat().coerceAtLeast(dp(6).toFloat()))
        val ny = y.coerceIn(dp(6).toFloat(), (parent.height - size - dp(6)).toFloat().coerceAtLeast(dp(6).toFloat()))
        // 松手后正贴过去的就是这里（刚存的位置又推回来了）：让动画走完，不然球一下跳过去
        if (settling && abs(nx - restX) < 1f && abs(ny - restY) < 1f) return
        restX = nx
        restY = ny
        settling = false
        val center = if (isOpen) placement() else null
        val tx = center?.let { it.cx * d - size / 2f } ?: restX
        val ty = center?.let { it.cy * d - size / 2f } ?: restY
        ball.animate().cancel()
        if (animate) ball.animate().translationX(tx).translationY(ty).setDuration(280).setInterpolator(Ui.SOFT_SPRING).start()
        else {
            ball.translationX = tx
            ball.translationY = ty
        }
        if (isOpen) placeItems()
    }

    /**
     * modal：平时点一圈外面就关掉（也挡住误点到电脑画面）。
     * 调整布局时这一圈一直开着，不能挡：语音条、键鼠条、侧栏还要能点、能拖。
     */
    fun open(views: List<View>, modal: Boolean = true) {
        ring.removeAllViews()
        items = views
        views.forEach { ring.addView(it, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.TOP or Gravity.START)) }
        ring.isClickable = modal
        ring.visibility = View.VISIBLE
        ball.text = "×"
        ball.contentDescription = "关掉常用按钮"
        ring.post { layoutRing(animate = true) }
    }

    fun close() {
        ring.visibility = View.GONE
        ring.removeAllViews()
        items = emptyList()
        ball.text = ""
        ball.contentDescription = "悬浮球：点一下打开常用按钮，双击一起收起或展开语音条、键鼠条，按住拖动"
        // 打开时球挪到了圈中间：回到原来停的地方
        if (!dragging && (ball.translationX != restX || ball.translationY != restY)) {
            ball.animate().translationX(restX).translationY(restY).setDuration(230).setInterpolator(Ui.SOFT_SPRING).start()
        }
    }

    /** 摆好一圈按钮；没在拖的话，球挪到圈中间（球靠边角时圈中心跟球心不在一处） */
    private fun layoutRing(animate: Boolean) {
        if (!placeItems() || dragging || !isOpen) return
        val p = placement() ?: return
        val x = p.cx * d - size / 2f
        val y = p.cy * d - size / 2f
        if (ball.translationX == x && ball.translationY == y) return
        if (animate) ball.animate().translationX(x).translationY(y).setDuration(230).setInterpolator(Ui.SOFT_SPRING).start()
        else {
            ball.translationX = x
            ball.translationY = y
        }
    }

    /**
     * 这一圈朝哪开、中心在哪（dp，见 BallRingLayout.place）。拖着球时按手指下的位置算，平时按球停的地方（rest）算。
     * 父容器还没量出尺寸返回 null
     */
    private fun placement(): BallRingLayout.Placement? {
        val parent = ring.parent as? View ?: return null
        if (parent.width <= 0 || parent.height <= 0) return null
        val bx = (if (dragging) ball.translationX else restX) + size / 2f
        val by = (if (dragging) ball.translationY else restY) + size / 2f
        return BallRingLayout.place(items.size, bx / d, by / d, parent.width / d, parent.height / d)
    }

    /** 按圈中心摆一圈按钮；父容器还没量出尺寸返回 false */
    private fun placeItems(): Boolean {
        val parent = ring.parent as? View ?: return false
        val w = parent.width
        val h = parent.height
        val p = placement() ?: return false
        val cx = p.cx * d
        val cy = p.cy * d
        BallRingLayout.spots(items.size).forEachIndexed { i, spot ->
            val a = Math.toRadians(p.angleOf(spot).toDouble())
            val r = spot.radiusDp * d
            val x = cx + (r * cos(a)).toFloat() - dp(29)
            val y = cy + (r * sin(a)).toFloat() - dp(29)
            // place 已经保证放得进屏幕；屏幕太小（分屏）时至少别出屏幕
            items[i].x = x.coerceIn(dp(4).toFloat(), (w - dp(62)).toFloat().coerceAtLeast(dp(4).toFloat()))
            items[i].y = y.coerceIn(dp(4).toFloat(), (h - dp(62)).toFloat().coerceAtLeast(dp(4).toFloat()))
        }
        return true
    }

    /** 一圈里离屏幕坐标 (rawX, rawY) 最近的那个按钮的下标（调整布局时拖着换位置用） */
    fun nearest(rawX: Float, rawY: Float): Int {
        var best = -1
        var bestDist = Float.MAX_VALUE
        val loc = IntArray(2)
        items.forEachIndexed { i, v ->
            v.getLocationOnScreen(loc)
            val dist = hypot(rawX - (loc[0] + v.width / 2f), rawY - (loc[1] + v.height / 2f))
            if (dist < bestDist) { bestDist = dist; best = i }
        }
        return best
    }

    /** 一圈里的圆形按钮：上面图标、下面小字；开着的开关黑底 */
    fun circle(label: String, glyph: String?, on: Boolean, onClick: () -> Unit): LinearLayout {
        val context = ball.context
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = label
            background = Ui.round(if (on) palette.fg else palette.tile, dp(29).toFloat(),
                if (on) 0 else Ui.hairline(context).toInt(), palette.line)
            elevation = dp(5).toFloat()
            addView(TextView(context).apply {
                text = glyph ?: label.take(1)
                gravity = Gravity.CENTER
                textSize = if ((glyph ?: "").length > 2) 13f else 20f
                typeface = Ui.font(context, 700)
                setTextColor(if (on) palette.tile else palette.fg)
            }, LinearLayout.LayoutParams(-1, dp(27)))
            addView(TextView(context).apply {
                text = label
                maxLines = 1
                gravity = Gravity.CENTER
                textSize = 9.5f
                typeface = Ui.font(context, 720)
                setTextColor(if (on) palette.tile else palette.sub)
            }, LinearLayout.LayoutParams(-1, dp(16)))
            setOnClickListener {
                Ui.buzz(it, Ui.Buzz.TAP)
                onClick()
            }
        }
    }

    /** 跟原来一样的同心圆悬浮球 */
    private class AssistiveBall(context: Context, private val colors: Ui.Palette) : TextView(context) {
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            if (text.isNullOrEmpty()) {
                val x = width / 2f
                val y = height / 2f
                val r = minOf(width, height) * .34f
                ink.style = Paint.Style.FILL
                ink.color = Ui.alpha(colors.fg, .11f)
                canvas.drawCircle(x, y, r, ink)
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = resources.displayMetrics.density * 1.5f
                ink.color = Ui.alpha(colors.fg, .65f)
                canvas.drawCircle(x, y, r, ink)
                ink.style = Paint.Style.FILL
                ink.color = Ui.alpha(colors.fg, .30f)
                canvas.drawCircle(x, y, r * .42f, ink)
            }
            super.onDraw(canvas)
        }
    }
}
