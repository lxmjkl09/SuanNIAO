package dev.suanniao

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 工作台里的小方块按钮，外观沿用原来侧栏的按钮：白底圆角细边，开着的开关黑底白字。
 * 调整布局时给按钮套角标：红色「−」= 拿掉，灰色「锁」= 固定（只能换位置）。
 */
internal object WorkbenchTiles {
    fun tile(context: Context, label: String, glyph: String? = null, on: Boolean = false): TextView {
        val d = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = if (glyph == null || glyph == label) label else SpannableStringBuilder(glyph).apply {
                setSpan(RelativeSizeSpan(1.25f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append("\n")
                val start = length
                append(label)
                setSpan(RelativeSizeSpan(0.82f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            contentDescription = label
            gravity = Gravity.CENTER
            maxLines = 2
            textSize = 11f
            typeface = Ui.font(context, 720)
            includeFontPadding = false
            setPadding((3 * d).toInt(), (2 * d).toInt(), (3 * d).toInt(), (2 * d).toInt())
            setOn(this, on)
        }
    }

    /**
     * 名字下面带一行小字的按钮（快捷发送里「打完按回车」的标「⏎ 带回车」）。
     * 名字只占一行：太长就截短加「…」，小字总看得见。
     */
    fun noted(context: Context, label: String, note: String): TextView = tile(context, label).apply {
        val chars = label.codePointCount(0, label.length)
        val shortLabel = if (chars <= 7) label else label.substring(0, label.offsetByCodePoints(0, 6)) + "…"
        text = SpannableStringBuilder(shortLabel).apply {
            append("\n")
            val start = length
            append(note)
            setSpan(RelativeSizeSpan(0.78f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        contentDescription = "$label，$note"
    }

    /** 开关类（只看画面、铺满屏幕、锁定方向、按住）开着时黑底白字 */
    fun setOn(view: TextView, on: Boolean) {
        val palette = Ui.palette(view.context)
        val d = view.resources.displayMetrics.density
        view.setTextColor(if (on) palette.tile else palette.fg)
        view.background = Ui.round(if (on) palette.fg else palette.tile, 13 * d,
            if (on) 0 else Ui.hairline(view.context).toInt(), palette.line)
    }

    /** 修饰键「一次」：粗边框，还没按下去 */
    fun setArmed(view: TextView) {
        val palette = Ui.palette(view.context)
        val d = view.resources.displayMetrics.density
        view.setTextColor(palette.fg)
        view.background = Ui.round(palette.tile, 13 * d, (2 * d).toInt(), palette.fg)
    }

    /** 调整布局时的角标；badge 为 null 就原样返回 */
    fun withBadge(tile: View, badge: String?, onBadge: (() -> Unit)?): View {
        if (badge == null) return tile
        val context = tile.context
        val d = context.resources.displayMetrics.density
        val remove = badge == "−"
        return FrameLayout(context).apply {
            clipChildren = false
            addView(tile, FrameLayout.LayoutParams(-1, -1))
            addView(TextView(context).apply {
                text = badge
                gravity = Gravity.CENTER
                textSize = if (remove) 14f else 8f
                includeFontPadding = false
                setTextColor(Color.WHITE)
                typeface = Ui.font(context, 900)
                background = Ui.round(if (remove) 0xFFE5362B.toInt() else 0xFF8D8D88.toInt(), 9 * d)
                contentDescription = if (remove) "拿掉" else "固定"
                if (onBadge != null) setOnClickListener { onBadge() }
            }, FrameLayout.LayoutParams((18 * d).toInt(), (18 * d).toInt(), Gravity.TOP or Gravity.END))
        }
    }

    /**
     * 浮在电脑画面上的条、面板：按钮之间的空隙、边上的留白也接住手指，不漏到下面的画面上点到电脑。
     * 只在里面的按钮、拖动把手都没接的时候才轮到这里，所以它们照常能点、能拖。不当成能点的按钮（读屏不念「点按两下」）
     */
    @SuppressLint("ClickableViewAccessibility")
    fun holdTouches(panel: View) = panel.setOnTouchListener { _, _ -> true }

    /** 空槽位或「加按钮」：虚线框 */
    fun plus(context: Context, label: String = "＋"): TextView = tile(context, label).apply {
        val palette = Ui.palette(context)
        val d = resources.displayMetrics.density
        background = GradientDrawable().apply {
            cornerRadius = 13 * d
            setColor(Ui.alpha(palette.tile, .6f))
            setStroke((1.5f * d).toInt(), palette.sub, 5 * d, 4 * d)
        }
        setTextColor(palette.sub)
        contentDescription = "加按钮"
    }
}
