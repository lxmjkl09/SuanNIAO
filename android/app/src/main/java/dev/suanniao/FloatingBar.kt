package dev.suanniao

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 语音条、键鼠条共用的浮条，一套交互：
 * - 标题「⠿ 名字」：点一下收起成小把手（还在屏幕上），按住拖动；
 * - 小把手：点一下展开，按住拖动；录音时变红、写「录音中」；
 * - 没有 ×：隐藏统一在悬浮球里点；
 * - 调整布局时：虚线框，拖标题挪位置，拉右下角蓝点改大小，点标题不收起。
 * - 竖条（v3.3 U8）：条宽约 70dp，主按钮竖着排；展开区不在条里往下长，放进条旁边的弹出块（pop），位置由 ScreenFocusControls 摆；
 * - 闲时半透明（v3.3 U7）：开着时条和小把手 3 秒没被碰就变淡，一按下马上恢复；录音、开着弹出块、调整布局时不变淡。
 * 条、小把手、弹出块都用「左上角 + translation」摆（父容器 gravity 是 TOP|START），换算成比例由 ScreenFocusControls 负责。
 */
@SuppressLint("ClickableViewAccessibility")
internal class FloatingBar(context: Context, val title: String) {
    private val d = context.resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()
    private val palette = Ui.palette(context)

    val header = TextView(context).apply {
        textSize = 13f
        typeface = Ui.font(context, 850)
        setTextColor(palette.fg)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8f), 0, dp(8f), 0)
        maxLines = 1
        contentDescription = "$title：点一下收起，按住拖动"
    }
    val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val extra = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(0, dp(5f), 0, 0)
    }
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(7f), dp(3f), dp(7f), dp(7f))
        addView(header, LinearLayout.LayoutParams(-1, dp(32f)))
        addView(body, LinearLayout.LayoutParams(-1, -2))
        addView(extra, LinearLayout.LayoutParams(-1, -2))
    }
    private val resize = View(context).apply {
        visibility = View.GONE
        background = Ui.round(0xFF2F6FED.toInt(), dp(12f).toFloat(), dp(3f), 0xFFFFFFFF.toInt())
        contentDescription = "拖动改大小"
    }
    // 条、小把手、弹出块上一按下都先告诉闲时半透明（按钮自己照常接手指）
    val bar: FrameLayout = object : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            onTouchActivity(ev)
            return super.dispatchTouchEvent(ev)
        }
    }.apply {
        clipChildren = false
        clipToPadding = false
        background = Ui.round(Ui.alpha(palette.tile, .97f), dp(18f).toFloat(), Ui.hairline(context).toInt(), palette.line)
        elevation = dp(8f).toFloat()
        visibility = View.GONE
        addView(column, FrameLayout.LayoutParams(-1, -2))
        addView(resize, FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, -dp(10f), -dp(10f))
        })
    }
    val pill: TextView = object : TextView(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            onTouchActivity(event)
            return super.dispatchTouchEvent(event)
        }
    }.apply {
        textSize = 13f
        typeface = Ui.font(context, 850)
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(12f), 0, dp(14f), 0)
        elevation = dp(8f).toFloat()
        visibility = View.GONE
        contentDescription = "$title（收起了）：点一下展开，按住拖动"
    }
    /** 竖条的弹出块（v3.3 U8）：展开区从条旁边弹出来，条本身不变大。宽度固定，位置由 ScreenFocusControls 摆 */
    val pop: FrameLayout = object : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            onTouchActivity(ev)
            return super.dispatchTouchEvent(ev)
        }
    }.apply {
        visibility = View.GONE
        setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        background = Ui.round(Ui.alpha(palette.tile, .97f), dp(14f).toFloat(), Ui.hairline(context).toInt(), palette.line)
        // 比两条、侧栏都高一点：弹出来就是要用它
        elevation = dp(10f).toFloat()
    }

    var onCollapse: (() -> Unit)? = null
    var onExpand: (() -> Unit)? = null
    var onBarDragged: (() -> Unit)? = null
    var onPillDragged: (() -> Unit)? = null
    var onScaled: ((Float) -> Unit)? = null
    var clampBar: () -> Unit = {}
    var clampPill: () -> Unit = {}

    var editing = false
        set(value) {
            field = value
            renderEditing()
            wake()
        }

    /** 竖条（v3.3 U8）：展开区（extra）从条里搬到旁边的弹出块里；关掉搬回来 */
    var vertical = false
        set(value) {
            if (field == value) return
            field = value
            (extra.parent as? ViewGroup)?.removeView(extra)
            if (value) {
                extra.setPadding(0, 0, 0, 0)
                pop.addView(extra, FrameLayout.LayoutParams(-1, -2))
                column.setPadding(dp(5f), dp(3f), dp(5f), dp(6f))
            } else {
                extra.setPadding(0, dp(5f), 0, 0)
                column.addView(extra, LinearLayout.LayoutParams(-1, -2))
                column.setPadding(dp(7f), dp(3f), dp(7f), dp(7f))
            }
            renderEditing()
            renderPop()
        }

    /** 展开区要不要显示：横条是条里那一截，竖条是旁边的弹出块（条收起时弹出块跟着藏） */
    private var extraWanted = false

    /** 弹出块开着（看得见） */
    val popOpen: Boolean get() = pop.visibility == View.VISIBLE

    /** 闲时半透明开着（侧栏开关，v3.3 U7） */
    var fadeWhenIdle = false
        set(value) {
            if (field == value) return
            field = value
            wake()
        }
    private var recording = false
    /** 手指正按在条、小把手或弹出块上 */
    private var touching = false
    private val fadeTask = Runnable { if (fadeWhenIdle && !touching && !holdOpaque()) setFaded(true) }
    private var fadeAnim: ValueAnimator? = null

    init {
        renderEditing()
        setRecording(false)
        // 按钮之间的空隙点下去不漏到电脑画面上（小把手本来就整个接住手指）
        WorkbenchTiles.holdTouches(bar)
        WorkbenchTiles.holdTouches(pop)
        header.setOnClickListener { if (!editing) onCollapse?.invoke() }
        bindFloatingToolDrag(header, bar, dp(6f).toFloat(), { clampBar() }) { onBarDragged?.invoke() }
        pill.setOnClickListener { onExpand?.invoke() }
        bindFloatingToolDrag(pill, pill, dp(6f).toFloat(), { clampPill() }) { onPillDragged?.invoke() }
        var startX = 0f
        var startScale = 1f
        resize.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX
                    startScale = bar.scaleX
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val base = bar.width * startScale
                    if (base > 0f) setScale((startScale * (base + e.rawX - startX) / base).coerceIn(0.6f, 1.8f))
                    clampBar()
                }
                MotionEvent.ACTION_UP -> onScaled?.invoke(bar.scaleX)
                MotionEvent.ACTION_CANCEL -> setScale(startScale)
            }
            true
        }
    }

    fun attach(parent: FrameLayout) {
        parent.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        parent.addView(pill, FrameLayout.LayoutParams(-2, dp(36f), Gravity.TOP or Gravity.START))
        parent.addView(pop, FrameLayout.LayoutParams(dp(POP_WIDTH_DP), -2, Gravity.TOP or Gravity.START))
    }

    fun show(state: WorkbenchSession.State) {
        bar.visibility = if (state == WorkbenchSession.State.OPEN) View.VISIBLE else View.GONE
        pill.visibility = if (state == WorkbenchSession.State.COLLAPSED) View.VISIBLE else View.GONE
        renderPop()
        wake()
    }

    fun setWidth(px: Int) {
        val lp = bar.layoutParams ?: return
        if (lp.width != px) {
            lp.width = px
            bar.layoutParams = lp
        }
    }

    fun setScale(k: Float) {
        bar.pivotX = 0f
        bar.pivotY = 0f
        bar.scaleX = k
        bar.scaleY = k
    }

    fun setExtraVisible(visible: Boolean) {
        extraWanted = visible
        renderPop()
        wake()
    }

    /** 横条：展开区在条里显示 / 不显示；竖条：展开区一直在弹出块里，弹出块跟着条显示 */
    private fun renderPop() {
        if (vertical) {
            extra.visibility = View.VISIBLE
            pop.visibility = if (extraWanted && bar.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        } else {
            extra.visibility = if (extraWanted) View.VISIBLE else View.GONE
            pop.visibility = View.GONE
        }
    }

    fun setRecording(recording: Boolean) {
        pill.text = if (recording) "⠿  录音中" else "⠿  $title"
        pill.setTextColor(if (recording) 0xFFFFFFFF.toInt() else palette.fg)
        pill.background = Ui.round(if (recording) palette.red else Ui.alpha(palette.tile, .97f), dp(18f).toFloat(),
            if (recording) 0 else Ui.hairline(pill.context).toInt(), palette.line)
        if (this.recording != recording) {
            this.recording = recording
            wake()
        }
    }

    // ───────── 闲时半透明 ─────────

    /** 正在录音、开着弹出块、调整布局时一直不透明 */
    private fun holdOpaque() = editing || recording || popOpen

    /** 现在变回不透明，再重新计 3 秒（开关关着就一直不透明） */
    fun wake() {
        bar.removeCallbacks(fadeTask)
        setFaded(false)
        if (fadeWhenIdle && !touching) bar.postDelayed(fadeTask, IDLE_MS)
    }

    private fun onTouchActivity(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                bar.removeCallbacks(fadeTask)
                setFaded(false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                wake()
            }
        }
    }

    /** 变淡慢一点（不晃眼），恢复马上（手指按下去就要看清） */
    private fun setFaded(faded: Boolean) {
        fadeAnim?.cancel()
        fadeAnim = null
        val target = if (faded) IDLE_ALPHA else 1f
        if (!faded || bar.alpha == target) {
            bar.alpha = target
            pill.alpha = target
            return
        }
        fadeAnim = ValueAnimator.ofFloat(bar.alpha, target).apply {
            duration = 320
            interpolator = Ui.EASE
            addUpdateListener {
                val a = it.animatedValue as Float
                bar.alpha = a
                pill.alpha = a
            }
            start()
        }
    }

    private fun renderEditing() {
        resize.visibility = if (editing) View.VISIBLE else View.GONE
        // 竖条只有 70dp 宽：标题只写名字，调整布局看虚线框
        header.text = when {
            vertical -> "⠿ $title"
            editing -> "⠿  $title · 拖标题挪，拉蓝点改大小"
            else -> "⠿  $title"
        }
        header.textSize = if (vertical) 12f else 13f
        header.setPadding(dp(if (vertical) 3f else 8f), 0, dp(if (vertical) 3f else 8f), 0)
        bar.foreground = if (editing) GradientDrawable().apply {
            cornerRadius = dp(18f).toFloat()
            setStroke(dp(2f), 0xFF2F6FED.toInt(), dp(6f).toFloat(), dp(4f).toFloat())
        } else null
    }

    companion object {
        /** 竖条多宽 */
        const val VERTICAL_WIDTH_DP = 70f
        /** 竖条弹出块多宽：一排四个按钮 */
        const val POP_WIDTH_DP = 236f
        /** 闲时半透明：多久没碰变淡、淡成多少 */
        const val IDLE_MS = 3000L
        const val IDLE_ALPHA = 0.35f
    }
}
