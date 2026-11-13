package dev.suanniao

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 侧栏：平时只是屏幕右边一根小竖条，点一下展开、再点一下收起（只能点，不能划：小米边缘划是「返回」）。
 * 展开后是设置（分三组）和「我的按钮」，全部一眼看到，不滑动；点里面任何一项，做完侧栏自己收起（ScreenFocusControls 管）。
 * 内容由 ScreenFocusControls 生成后塞进来（setContent），这里只管摆；怎么摆见 RailFit（v3.3 U1）：
 * 横屏塞进画面右边的空白（一列窄按钮，不挪画面、不盖画面），空白不够才盖在画面右边；竖屏放到画面下面（keepBelow）。
 * 小竖条一直在最右边，面板展开在它左边。
 */
internal class WorkbenchRail(context: Context) {
    private val d = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun dp(v: Float) = (v * d).roundToInt()
    private val palette = Ui.palette(context)

    private val handle = FrameLayout(context).apply {
        background = Ui.round(Ui.alpha(palette.tile, .95f), dp(10).toFloat(), Ui.hairline(context).toInt(), palette.line)
        elevation = dp(6).toFloat()
        contentDescription = "侧栏：点一下展开或收起"
        addView(View(context).apply {
            background = Ui.round(Ui.alpha(palette.fg, .45f), dp(2).toFloat())
        }, FrameLayout.LayoutParams(dp(4), dp(42), Gravity.CENTER))
    }
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = Ui.round(Ui.alpha(palette.tile, .96f), dp(16).toFloat(), Ui.hairline(context).toInt(), palette.line)
        elevation = dp(8).toFloat()
    }
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        // 面板在左、小竖条在右：展开、收起时小竖条都待在最右边不动
        addView(panel, LinearLayout.LayoutParams(-2, -2))
        addView(handle, LinearLayout.LayoutParams(dp(16), dp(96)).apply { marginStart = dp(RailFit.HANDLE_GAP) })
    }

    var onToggle: (() -> Unit)? = null

    /**
     * 竖屏时电脑画面的下边（像素，跟父容器同一个坐标；0 = 不用让）。侧栏放在它下面，不盖住画面；
     * 下面放不下就贴着屏幕底，尽量少盖。横屏是 0（横屏塞进画面右边的空白，竖直居中）
     */
    var keepBelow = 0
        set(value) {
            if (field == value) return
            field = value
            reposition()
        }

    init {
        handle.setOnClickListener {
            Ui.buzz(it, Ui.Buzz.TAP)
            onToggle?.invoke()
        }
        // 面板里按钮之间的空隙、小竖条和面板中间的缝点下去不漏到电脑画面上
        WorkbenchTiles.holdTouches(root)
        // 展开、收起、换内容、转屏以后高度变了：重新算竖屏时放在画面下面的位置
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reposition() }
    }

    fun attach(parent: FrameLayout) {
        // 竖直居中交给布局：展开、收起、转屏后高度变了也自己居中
        parent.addView(root, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(RailFit.EDGE_END) })
    }

    fun setOpen(open: Boolean) {
        panel.visibility = if (open) View.VISIBLE else View.GONE
        // 展开时盖在语音条、键鼠条、悬浮球上面（竖屏画面下面就是这几条）：点开就是要用它；
        // 不压过顶上的状态小条（9dp）和调整布局的顶栏
        root.translationZ = if (open) 8.5f * d else 0f
    }

    /** 竖屏：放在画面下面（keepBelow 以下居中）；放不下就贴底。用位移挪，不重新排版 */
    private fun reposition() {
        val parent = root.parent as? View ?: return
        val h = root.height
        if (parent.height <= 0 || h <= 0) return
        var top = root.top.toFloat()
        if (keepBelow > 0) {
            val room = parent.height - keepBelow
            top = (keepBelow + (room - h) / 2f).coerceAtLeast(keepBelow + dp(6).toFloat())
            if (top + h > parent.height - dp(6)) top = (parent.height - dp(6) - h).toFloat().coerceAtLeast(0f)
        }
        root.translationY = top - root.top
    }

    /**
     * groups：设置的几组（组之间细线隔开）；mine：「我的按钮」；plus：调整布局时的「＋」；
     * plan：排几列、按钮多大（RailFit 算的）。塞进空白时按钮窄，字放不下就自己缩小一点（最小 7sp）
     */
    fun setContent(groups: List<List<View>>, mine: List<View>, plus: View?, plan: RailFit.Plan) {
        panel.removeAllViews()
        val pad = if (plan.fit) dp(RailFit.PAD) else dp(6)
        panel.setPadding(pad, pad, pad, pad)
        val gap = if (plan.fit) dp(RailFit.GAP) else dp(4)
        val cellW = dp(plan.cellW)
        val width = plan.columns * cellW + (plan.columns - 1) * gap
        fun row(views: List<View>) = FlowRow(panel.context, plan.columns, dp(plan.cellH), gap).apply {
            views.forEach { v ->
                if (plan.fit && v is TextView) {
                    v.maxLines = 2
                    v.setAutoSizeTextTypeUniformWithConfiguration(7, plan.textSp, 1, TypedValue.COMPLEX_UNIT_SP)
                }
                addView(v)
            }
        }
        groups.forEachIndexed { i, group ->
            if (i > 0) panel.addView(divider(), LinearLayout.LayoutParams(width, if (plan.fit) dp(RailFit.DIVIDER) else dp(9)))
            panel.addView(row(group), LinearLayout.LayoutParams(width, -2))
        }
        if (mine.isNotEmpty() || plus != null) {
            panel.addView(TextView(panel.context).apply {
                text = "我的按钮"
                maxLines = 1
                textSize = 10f
                if (plan.fit) setAutoSizeTextTypeUniformWithConfiguration(6, 9, 1, TypedValue.COMPLEX_UNIT_SP)
                typeface = Ui.font(context, 800)
                setTextColor(palette.sub)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(width, if (plan.fit) dp(RailFit.CAPTION) else dp(22)))
            panel.addView(row(mine + listOfNotNull(plus)), LinearLayout.LayoutParams(width, -2))
        }
    }

    private fun divider() = FrameLayout(panel.context).apply {
        addView(View(context).apply { setBackgroundColor(palette.line) },
            FrameLayout.LayoutParams(-1, Ui.hairline(context).toInt().coerceAtLeast(1), Gravity.CENTER_VERTICAL))
    }
}

/**
 * 侧栏怎么摆（v3.3 U1），只算数（dp），好测：
 * - 横屏、画面完整显示、右边空白够宽：一列窄按钮塞进空白，不挪画面也不盖画面。按钮宽 = 空白宽 − 两边要留的（最多 72dp），
 *   按钮高按屏幕高能放下几个算（24–40dp）。约定写的是 34–40dp，可侧栏现在有 11 个设置按钮（加上「闲时半透明」「竖条」），
 *   393dp 高的手机横屏一列只放得下 32dp 左右；再有几个「我的按钮」还要更矮。按钮之间、组之间也排得紧一点；
 * - 空白不够（窄于 56dp，或者算出来的按钮窄得放不下字）、开着「铺满屏幕」、一列矮到 24dp 也放不下：盖在画面右边，
 *   两列、按钮跟原来一样大，仍然不挪画面；
 * - 竖屏：一列、跟原来一样大，放在画面下面。
 */
internal object RailFit {
    /** fit = 塞进空白；columns 列，按钮 cellW × cellH（dp），字最大 textSp */
    data class Plan(val fit: Boolean, val columns: Int, val cellW: Float, val cellH: Float, val textSp: Int)

    /** 空白比这窄就盖在画面上（约定） */
    const val MIN_BLANK = 56f
    const val MAX_CELL_W = 72f
    /** 按钮再窄，四个字缩到最小也两行放不下：也改成盖在画面上 */
    const val MIN_CELL_W = 34f
    const val MAX_CELL_H = 40f
    /** 再矮一行字（缩到最小）都放不下、手指也不好点：改成盖在画面上 */
    const val MIN_CELL_H = 24f
    /** 侧栏离屏幕右边 */
    const val EDGE_END = 2f
    /** 小竖条和面板之间的缝 */
    const val HANDLE_GAP = 3f
    const val HANDLE_W = 16f
    /** 塞进空白时面板的内边距 */
    const val PAD = 3f
    /** 塞进空白时面板和画面之间至少隔这么多 */
    const val PICTURE_GAP = 1f
    /** 一列塞进空白时，横着除了按钮宽以外要留的：屏幕边 2 + 小竖条 16 + 缝 3 + 面板两边内边距 3×2 + 跟画面隔 1 */
    const val SIDE = EDGE_END + HANDLE_W + HANDLE_GAP + 2 * PAD + PICTURE_GAP
    /** 塞进空白时：按钮之间、组之间的细线、「我的按钮」那行小字 */
    const val GAP = 2f
    const val DIVIDER = 5f
    const val CAPTION = 12f
    /** 面板离屏幕上下边至少留多少 */
    const val EDGE = 4f

    /** 横屏盖在画面右边：两列，跟原来一样大 */
    val OVERLAY = Plan(false, 2, 64f, 44f, 11)
    /** 竖屏：一列，放在画面下面 */
    val PORTRAIT = Plan(false, 1, 64f, 44f, 11)

    /**
     * blank：横屏画面右边到屏幕右边有多宽；height：屏幕多高；fill：开着「铺满屏幕」；
     * sections：每一段几个按钮（设置几组，最后可能是「我的按钮」）；mine：最后一段是「我的按钮」（上面是一行小字，不是细线）
     */
    fun plan(blank: Float, height: Float, fill: Boolean, sections: List<Int>, mine: Boolean): Plan {
        if (fill || blank < MIN_BLANK) return OVERLAY
        val cellW = minOf(MAX_CELL_W, blank - SIDE)
        if (cellW < MIN_CELL_W) return OVERLAY
        val tiles = sections.sum().coerceAtLeast(1)
        val breaks = (sections.size - 1).coerceAtLeast(0)
        val separators = if (mine && breaks > 0) (breaks - 1) * DIVIDER + CAPTION else breaks * DIVIDER
        val gaps = sections.sumOf { (it - 1).coerceAtLeast(0) } * GAP
        val room = height - 2 * EDGE - 2 * PAD - separators - gaps
        val cellH = minOf(MAX_CELL_H, room / tiles)
        if (cellH < MIN_CELL_H) return OVERLAY
        return Plan(true, 1, cellW, cellH, if (cellW >= 52f && cellH >= 34f) 11 else 10)
    }
}
