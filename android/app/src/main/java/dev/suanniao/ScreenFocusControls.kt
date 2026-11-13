package dev.suanniao

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import dev.suanniao.WorkbenchLayoutV3.Container
import dev.suanniao.WorkbenchLayoutV3.Slot
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 全屏远程工作台：电脑画面上面的悬浮球、侧栏（右边小竖条）、语音条、键鼠条。
 * 一套交互：点浮条标题 = 收起成小把手（还在屏幕上）；悬浮球里点「语音」「键鼠」= 显示 / 隐藏；双击悬浮球 = 两条一起收起 / 展开。
 * 放什么由电脑上的工作台布局（WorkbenchLayoutV3）决定；展开 / 收起 / 拖到哪只记在手机上（WorkbenchSession）。
 * 两条都展开又叠在一起时，显示时把一条挪开（BarStacking），存着的位置不变。
 * 转屏只按另一个方向记住的位置挪，不重新搭视图：录音、按住的修饰键、按住的左键都不会断。
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class ScreenFocusControls(
    context: Context,
    private val host: Host,
    private val mic: VoiceButton,
) : FrameLayout(context) {

    /** MainActivity 实现：发给电脑、改画面的事都走这里。返回 false = 没发出去（Host 自己提示原因） */
    interface Host {
        fun sendKey(key: String, modifiers: List<String>): Boolean
        fun sendButton(id: String, trigger: String): Boolean
        fun sendText(text: String): Boolean
        fun sendTaskSwitch(action: String): Boolean
        fun sendModifier(key: String, down: Boolean): Boolean
        fun sendToPc(message: JSONObject): Boolean
        fun armSelection()
        fun cancelSelection()
        fun setInputMode(mode: ScreenView.InputMode)
        fun toggleFill()
        fun isFill(): Boolean
        fun resetZoom()
        fun toggleRotationLock()
        fun isRotationLocked(): Boolean
        fun leftClick()
        fun rightClick()
        fun scroll(direction: Int)
        fun toggleHold(): Boolean
        fun setCursorAppearance(style: ScreenView.CursorStyle, size: Float)
        fun notice(text: String)
        fun close()
        /** 「发图片」：跟正式版「⋯ → 发文件到电脑」一样，系统选文件（能多选）后发到电脑 */
        fun pickFiles()
        /** 侧栏「语音设置」：读、改的都是 ⋯ 菜单「语音识别」那几项（改完全屏的语音按钮马上跟着变） */
        fun voiceSettings(): VoiceSettings
        fun setVoiceSettings(choice: String, liveTyping: Boolean)
        /**
         * 「全部松开」：电脑在哪条连接上都认（蓝牙也行）。修饰键、按住的左键、切换应用按着的 Alt 只能走局域网松开，
         * 局域网断了就用它，不能只把手机上的按钮改回去假装松开了（v3.2 K7）。
         * 返回 true = 发出去了；false = 一条连接都没有，连上以后自动补发
         */
        fun releaseEverything(): Boolean
        /** 竖屏电脑画面的下边（像素，跟工作台同一个坐标）：侧栏放到它下面。横屏、铺满屏幕、还没画面时返回 0 */
        fun pictureBottom(): Int
        /**
         * 横屏完整显示时电脑画面的右边（像素，跟工作台同一个坐标，不算双指放大）：侧栏塞进它右边的空白（v3.3 U1）。
         * 不知道桌面尺寸时返回 0（当成右边全是空白）
         */
        fun pictureRight(): Int
    }

    data class OfficialButton(val id: String, val label: String, val hasDouble: Boolean, val hasLong: Boolean, val confirm: Boolean)

    /** 一种识别方式：id 是手机上存的名字（空 = 没装，只显示不能选），title、note 跟 ⋯ 菜单里写的一样 */
    data class VoiceOption(val id: String, val title: String, val note: String)
    data class VoiceSettings(val options: List<VoiceOption>, val chosen: String, val liveTyping: Boolean)

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val palette = Ui.palette(context)
    private val session = WorkbenchSession.of(context.getSharedPreferences("screen_workbench_v3", Context.MODE_PRIVATE))
    private val latch = ModifierLatch()
    private val interaction = WorkbenchInteractionState()
    private val saveGate = WorkbenchSaveGate(10_000L)
    private var saveTimeout: Runnable? = null
    private var pendingReset: String? = null
    private var requestSeq = 0L
    /** 点了「完成」还没等到电脑确认就不保存了：那份布局的签名、记到什么时候（电脑其实存上了的话推回来时认得出，审查 #11） */
    private var abandonedSave: String? = null
    private var abandonedUntil = 0L

    internal var layout = WorkbenchLayoutV3.fallback()
        private set
    internal var layoutSupported = false
        private set
    internal var officialButtons: Map<String, OfficialButton> = emptyMap()
        private set
    internal var keyEditor = JSONObject()
        private set
    internal var profileId = ""
        private set
    private var doubleMs = 300L
    private var longMs = 500L

    private var editor: WorkbenchEditor? = null
    private val shown: WorkbenchLayoutV3 get() = editor?.draft ?: layout
    private val editing: Boolean get() = editor != null
    internal val isLandscape: Boolean get() = width > height

    private val voice = FloatingBar(context, "语音")
    private val keymouse = FloatingBar(context, "键鼠")
    private val ball = WorkbenchBall(context)
    private val rail = WorkbenchRail(context)
    private val stateChip = chip()
    private val modifierChip = chip()
    private val taskSwitchPanel = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        visibility = View.GONE
        setPadding(dp(5), dp(4), dp(5), dp(4))
        background = Ui.round(Ui.alpha(palette.tile, .94f), dp(14).toFloat(), Ui.hairline(context).toInt(), palette.line)
        elevation = dp(8).toFloat()
    }
    private var talkButton: TextView? = null
    /** 语音条第一行的「编辑▾」「快捷发送▾」和它们展开的那两排（同时只开一排） */
    private var editTab: TextView? = null
    private var quickTab: TextView? = null
    private var voiceEditRow: View? = null
    private var voiceQuickRow: View? = null
    /** 快捷发送里（不在调整布局时）发给电脑的新建、改、删：请求号 → 电脑存好以后说什么 */
    private val quickRequests = HashMap<String, String>()
    private var holdButton: TextView? = null
    private val modButtons = LinkedHashMap<String, TextView>()
    private var holdOn = false
    private var taskSwitcherActive = false
    private val taskSwitchTimeout = Runnable { finishTaskSwitch(false) }
    private val modifierTimeout = Runnable {
        if (latch.anyActive()) {
            // 松开发不出去时不能说「已经松开」：一条连接都没有就说清楚是连上以后才松开
            host.notice(if (releaseModifiers()) "Ctrl 等修饰键 10 秒没用，已经自动松开"
                else "Ctrl 等修饰键 10 秒没用：现在没连上电脑，连上后马上松开")
        }
    }
    private var keyboardDialog: Dialog? = null
    private var keyboardBox: VoiceBox? = null
    /** 手机键盘框里没发出去的字：只在这次全屏里留着，不存盘 */
    private var keyboardDraft = ""

    /** 竖条（v3.3 U8）：两条一起切，存在手机上 */
    private val vertical: Boolean get() = session.verticalBars
    /** 竖条时开着弹出块的那条（voice / keymouse，同时只开一个）；语音条开的是哪一排。只在这次全屏里记着，不存 */
    private var popName: String? = null
    private var popRow: WorkbenchSession.VoiceRow? = null
    /**
     * 竖条弹出块开着时垫在最底下：点到电脑画面上先收回弹出块，这一下不点到电脑上（跟点悬浮球那一圈外面一样）。
     * 条、小把手、悬浮球、侧栏都在它上面，照常能点
     */
    private val popScrim = View(context).apply {
        visibility = View.GONE
        setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) closePop()
            true
        }
    }
    /** 最后展开的是哪条：两条顶边一样高时挪它（v3.3 U2） */
    private var lastExpanded = "keymouse"
    /** 两条没让开时该在的地方（像素，已经拉回屏幕里）：收起时小把手按这里算，让开只是显示 */
    private val barHomes = HashMap<String, BarStacking.Box>()
    /** 两条现在显示在哪（让开以后的）：竖条的弹出块跟着它摆 */
    private val barShown = HashMap<String, BarStacking.Box>()
    /** 每条最近一次往哪挪（动画中途又算出同一个位置时不打断） */
    private val slides = HashMap<View, Slide>()
    private class Slide(val x: Float, val y: Float, val at: Long)
    private val barsTask = Runnable { placeBars(animate = false) }
    /** 侧栏上次怎么摆的、各段几个按钮（画面变了按新的空白重算，变了才重做按钮） */
    private var railPlan: RailFit.Plan? = null
    private var railSections: List<Int> = emptyList()
    private var railMine = false

    init {
        clipChildren = false
        clipToPadding = false
        rail.attach(this)
        voice.attach(this)
        keymouse.attach(this)
        ball.attach(this)
        addView(popScrim, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(taskSwitchPanel, LayoutParams(dp(345), dp(52), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(61) })
        addView(stateChip, LayoutParams(LayoutParams.WRAP_CONTENT, dp(31), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })
        addView(modifierChip, LayoutParams(LayoutParams.WRAP_CONTENT, dp(31), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(44) })
        // 「上一个 / 下一个」之间的空隙点下去不漏到电脑画面上
        WorkbenchTiles.holdTouches(taskSwitchPanel)
        // 原版语音按钮留着（识别引擎、发送逻辑都不变），只是藏起来，用语音条上的「点按开始说话」控制
        (mic.parent as? ViewGroup)?.removeView(mic)
        mic.visibility = View.INVISIBLE
        addView(mic, LayoutParams(1, 1))
        mic.onActiveChanged = { active -> onVoiceActivityChanged(active) }
        mic.onRecordingPhaseChanged = { _, _ -> renderVoicePhase() }
        wireBars()
        applyIdleFade()
        ball.onTap = { if (ball.isOpen) ball.close() else openRing() }
        ball.onDoubleTap = { toggleBothBars() }
        ball.onDragged = { onBallDragged() }
        rail.onToggle = { setRailOpen(!session.railOpen(isLandscape)) }
        buildTaskSwitchPanel()
        rebuildContents()
    }

    // ───────── 电脑推来的配置 ─────────

    fun updateConfig(config: JSONObject) {
        val buttons = LinkedHashMap<String, OfficialButton>()
        val pages = config.optJSONObject("layout")?.optJSONArray("pages")
        for (p in 0 until (pages?.length() ?: 0)) {
            val modules = pages!!.optJSONObject(p)?.optJSONArray("modules") ?: continue
            for (i in 0 until modules.length()) {
                val m = modules.optJSONObject(i) ?: continue
                if (m.optString("kind") != "button") continue
                val id = m.optString("id")
                if (id.isBlank()) continue
                val t = m.optJSONObject("triggers")
                buttons[id] = OfficialButton(id, m.optString("label").ifBlank { "按键" },
                    t?.optBoolean("doubleClick") == true, t?.optBoolean("longPress") == true, m.optBoolean("confirm"))
            }
        }
        val triggers = config.optJSONObject("triggers")
        doubleMs = triggers?.optLong("doubleClickMs", 300) ?: 300
        longMs = triggers?.optLong("longPressMs", 500) ?: 500
        val nextKeyEditor = config.optJSONObject("keyEditor") ?: JSONObject()
        profileId = config.optJSONObject("profile")?.optString("id").orEmpty()
        layoutSupported = config.optBoolean("screenWorkbenchV3Supported")
        val parsed = WorkbenchLayoutV3.parse(config.optJSONObject("screenWorkbenchV3"), buttons.keys)
        val next = parsed ?: WorkbenchLayoutV3.fallback()
        // 只改了「打完按回车」：名字、布局都没变，快捷发送上的「⏎」标记也要跟着重画
        val changed = next.signature() != layout.signature() || buttons != officialButtons ||
            enterIds(nextKeyEditor) != enterIds(keyEditor)
        keyEditor = nextKeyEditor
        officialButtons = buttons
        layout = next
        if (parsed != null && saveGate.onConfigEcho(parsed.signature())) {
            cancelSaveTimeout()
            // 调整布局点了「完成」：电脑确认了才退出（退出时整个重画）。
            // 快捷发送里「往前挪」不在调整布局里，照常往下走，按电脑推回来的布局重画
            if (editing) {
                finishEditing(saved = true)
                return
            }
        }
        // 点了「完成」又点「不保存」，可电脑在那之前已经存上了：跟存好时一样清掉平时拖出来的位置，说一声
        var repositioned = false
        abandonedSave?.let { signature ->
            val inTime = SystemClock.elapsedRealtime() <= abandonedUntil
            val echoed = parsed?.signature() == signature
            if (!inTime || echoed) abandonedSave = null
            if (inTime && echoed) {
                session.clearPositions()
                repositioned = true
                host.notice("电脑在你点「不保存」之前已经存上了刚才的布局")
            }
        }
        editor?.onConfig(parsed)
        if (changed) {
            rebuildContents()
            applyAll(animate = false)
        } else if (repositioned) applyAll(animate = true)
    }

    // ───────── 几条工具里的按钮（布局变了才重做，转屏不重做） ─────────

    private fun rebuildContents() {
        buildVoice()
        buildKeymouse()
        applyRail()
        if (ball.isOpen) openRing()
    }

    /**
     * 语音条第一行：点按开始说话 / 键盘输入 / 编辑▾ / 快捷发送▾。
     * 「编辑▾」「快捷发送▾」各展开一排，同时只开一排（开着哪排手机自己记，横屏竖屏各记各的）。
     * 竖条（v3.3 U8）：说话 / 键盘 / 编辑▾ / 快捷▾ 竖着排，展开的那一排从条旁边弹出来（只在这次全屏里记着）。
     */
    private fun buildVoice() {
        voice.vertical = vertical
        voice.body.removeAllViews()
        val talk = WorkbenchTiles.tile(context, if (vertical) "说话" else "点按开始说话").apply {
            textSize = if (vertical) 12f else 13f
            setOnClickListener { if (!editing) mic.toggleTapToTalk() }
        }
        talkButton = talk
        val keyboard = WorkbenchTiles.tile(context, if (vertical) "键盘" else "键盘输入").apply {
            setOnClickListener { if (!editing) showPhoneKeyboard() }
        }
        val edit = WorkbenchTiles.tile(context, "编辑▾").apply {
            setOnClickListener {
                if (editing) return@setOnClickListener
                // 有没发出去的字就打开手机键盘改，不先往电脑发退格、方向键
                if (keyboardDraft.isNotBlank()) showPhoneKeyboard() else toggleVoiceRow(WorkbenchSession.VoiceRow.EDIT)
            }
        }
        editTab = edit
        val quick = WorkbenchTiles.tile(context, if (vertical) "快捷▾" else "快捷发送▾").apply {
            // 横屏语音条窄：五个字放不下就把字缩小一点，不折行、不截掉
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(8, 11, 1, TypedValue.COMPLEX_UNIT_SP)
            setOnClickListener { if (!editing) toggleVoiceRow(WorkbenchSession.VoiceRow.QUICK) }
        }
        quickTab = quick
        if (vertical) {
            voice.body.addView(verticalColumn(listOf(talk, keyboard, edit, quick)), LinearLayout.LayoutParams(-1, -2))
        } else {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(talk, LinearLayout.LayoutParams(0, dp(44), 1.7f).apply { marginEnd = dp(4) })
            row.addView(keyboard, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) })
            row.addView(edit, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) })
            row.addView(quick, LinearLayout.LayoutParams(0, dp(44), 1f))
            voice.body.addView(row, LinearLayout.LayoutParams(-1, dp(44)))
        }
        voice.extra.removeAllViews()
        // 调整布局时两排都摆出来，各带一行小标题分清是哪排；平时只显示开着的那一排（renderVoiceRows）
        val editPart: View = if (editor == null) slotRow(Container.VOICE, columns = 4)
            else captioned("「编辑▾」里的按键", slotRow(Container.VOICE, columns = 4))
        val quickPart: View = if (editor == null) quickRow()
            else captioned("「快捷发送▾」里存好的话", slotRow(Container.QUICK, columns = 4)).apply { setPadding(0, dp(6), 0, 0) }
        voiceEditRow = editPart
        voiceQuickRow = quickPart
        voice.extra.addView(editPart, LinearLayout.LayoutParams(-1, -2))
        voice.extra.addView(quickPart, LinearLayout.LayoutParams(-1, -2))
        renderVoiceRows()
        renderVoicePhase()
    }

    /** 竖条的主按钮：一个一个往下排，40dp 高 */
    private fun verticalColumn(tiles: List<View>) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        tiles.forEachIndexed { i, t ->
            addView(t, LinearLayout.LayoutParams(-1, dp(40)).apply { if (i < tiles.size - 1) bottomMargin = dp(4) })
        }
    }

    /** 语音条下面（竖条是弹出块里）开着哪一排：平时只开一排（对应的标签黑底），调整布局时两排都摆出来 */
    private fun renderVoiceRows() {
        val open = when {
            editing -> null
            vertical -> popRow.takeIf { popName == "voice" }
            else -> session.voiceRow(isLandscape)
        }
        voiceEditRow?.visibility = if (editing || open == WorkbenchSession.VoiceRow.EDIT) View.VISIBLE else View.GONE
        voiceQuickRow?.visibility = if (editing || open == WorkbenchSession.VoiceRow.QUICK) View.VISIBLE else View.GONE
        editTab?.let { WorkbenchTiles.setOn(it, open == WorkbenchSession.VoiceRow.EDIT) }
        quickTab?.let { WorkbenchTiles.setOn(it, open == WorkbenchSession.VoiceRow.QUICK) }
    }

    private fun toggleVoiceRow(row: WorkbenchSession.VoiceRow) {
        if (vertical) {
            // 竖条：开着的正是这一排就收回弹出块，不然弹出这一排（另一条的弹出块收回）
            if (popName == "voice" && popRow == row) closePop() else openPop("voice", row)
            return
        }
        session.toggleVoiceRow(isLandscape, row)
        applyTool("voice", animate = false)
    }

    // ───────── 竖条的弹出块（v3.3 U8） ─────────

    /** 弹出 name 那条的展开区（语音条是 row 那一排）；另一条开着的收回 */
    private fun openPop(name: String, row: WorkbenchSession.VoiceRow? = null) {
        val other = popName?.takeIf { it != name }
        popName = name
        popRow = row
        other?.let { applyTool(it, animate = false) }
        applyTool(name, animate = false)
    }

    /** 收回弹出块（点了别处、再点一次▾、条收起了） */
    private fun closePop() {
        val name = popName ?: return
        popName = null
        popRow = null
        applyTool(name, animate = false)
    }

    /** 点别处收回弹出块的那层垫子：调整布局时两条的弹出块一直开着，不垫 */
    private fun renderPopScrim() {
        popScrim.visibility = if (!editing && (voice.popOpen || keymouse.popOpen)) View.VISIBLE else View.GONE
    }

    /**
     * 弹出块摆在条旁边：条在左半边就往右弹，在右半边就往左弹；那边放不下换另一边；上边跟条对齐，不出屏幕。
     * box：条显示在哪（null = 按条现在的位置，拖着条的时候用）
     */
    private fun placePop(name: String, box: BarStacking.Box? = null) {
        val b = bar(name)
        val p = b.pop
        if (p.visibility != View.VISIBLE || width <= 0 || height <= 0) return
        var pw = p.width
        var ph = p.height
        if (pw <= 0 || ph <= 0) {
            // 刚弹出来、还没排版：先量一下，第一帧就摆在条旁边，不在左上角闪一下
            val lpw = p.layoutParams?.width?.takeIf { it > 0 } ?: return
            p.measure(View.MeasureSpec.makeMeasureSpec(lpw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
            pw = p.measuredWidth
            ph = p.measuredHeight
        }
        val bx = box?.x ?: b.bar.translationX
        val by = box?.y ?: b.bar.translationY
        val bw = box?.w ?: (b.bar.width * b.bar.scaleX)
        val gap = dp(8).toFloat()
        val m = dp(6).toFloat()
        val toRight = bx + bw / 2 < width / 2f
        var x = if (toRight) bx + bw + gap else bx - pw - gap
        if (toRight && x + pw > width - m) x = bx - pw - gap
        if (!toRight && x < m) x = bx + bw + gap
        p.translationX = x.coerceIn(m, (width - m - pw).coerceAtLeast(m))
        p.translationY = by.coerceIn(m, (height - m - ph).coerceAtLeast(m))
    }

    // ───────── 快捷发送（语音条「快捷发送▾」那一排） ─────────

    /** 存好的话，4 列，点一下打进电脑；最后是「＋ 新建」和一行说明 */
    private fun quickRow(): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val row = FlowRow(context, 4, dp(42), dp(4))
        shown.quickText.forEach { ref ->
            if (ref is WorkbenchRef.Button) row.addView(quickTile(ref.id, row))
        }
        row.addView(WorkbenchTiles.plus(context, "＋ 新建").apply {
            contentDescription = "新建一条快捷发送"
            setOnClickListener { createQuickText() }
        })
        box.addView(row, LinearLayout.LayoutParams(-1, -2))
        box.addView(caption("点一下：打进电脑光标处 · 按住：改、删除 · 按住再拖：排顺序").apply { setPadding(0, dp(5), 0, 0) },
            LinearLayout.LayoutParams(-1, -2))
        return box
    }

    private fun quickTile(id: String, row: FlowRow): View {
        val label = officialButtons[id]?.label ?: "失效的按键"
        val tile = quickLabelTile(id, label)
        tile.ellipsize = TextUtils.TruncateAt.END
        // 读屏的「点按两下」「按住」也走同样的事
        tile.setOnClickListener { sendOfficial(id, "click", sentNotice = "已发：$label") }
        tile.setOnLongClickListener {
            showQuickMenu(id)
            true
        }
        bindQuickGestures(tile, id, row)
        return tile
    }

    /**
     * 快捷发送的一格：点一下发出去；按住不动松手 = 菜单（改 / 往前挪 / 挪到最前 / 删除）；按住以后拖 = 拖到别的格子排顺序。
     * 都按 id 做、不记下标：菜单开着、手指拖着的时候电脑推来了新顺序，也不会挪错（审查 #7）。
     * 没按够久就滑走了不算点（免得想拖语音条时误发一条）。
     */
    private fun bindQuickGestures(tile: View, id: String, row: FlowRow) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        var sx = 0f
        var sy = 0f
        var armed = false
        var dragging = false
        var slid = false
        val arm = Runnable {
            armed = true
            Ui.buzz(tile, Ui.Buzz.LONG)
            tile.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start()
        }
        fun reset(v: View) {
            v.removeCallbacks(arm)
            v.animate().cancel()
            v.isPressed = false
            v.scaleX = 1f
            v.scaleY = 1f
            v.translationX = 0f
            v.translationY = 0f
            v.translationZ = 0f
            v.alpha = 1f
        }
        tile.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    reset(v)
                    armed = false
                    dragging = false
                    slid = false
                    sx = e.rawX
                    sy = e.rawY
                    v.isPressed = true
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.postDelayed(arm, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (!armed) {
                        if (!slid && hypot(dx, dy) > slop) {
                            slid = true
                            v.removeCallbacks(arm)
                            v.isPressed = false
                        }
                    } else {
                        if (!dragging && hypot(dx, dy) > slop) {
                            dragging = true
                            v.translationZ = dp(6).toFloat() // 压在别的格子上面
                            v.alpha = .85f
                        }
                        if (dragging) {
                            // 语音条可能缩放过：位移按缩放换算，格子正好跟着手指走
                            val k = quickScale()
                            v.translationX = dx / k
                            v.translationY = dy / k
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val wasArmed = armed
                    val wasDragging = dragging
                    val wasSlid = slid
                    reset(v)
                    when {
                        wasDragging -> dropQuick(id, row, e.rawX, e.rawY)
                        wasArmed -> showQuickMenu(id)
                        !wasSlid -> v.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> reset(v)
            }
            true
        }
    }

    /** 拖完松手：落在这一排的第几格就挪到第几个；拖出这一排（上下各多算 24dp）就当不挪了 */
    private fun dropQuick(id: String, row: FlowRow, rawX: Float, rawY: Float) {
        val count = layout.quickText.size
        if (count < 2) return
        val loc = IntArray(2)
        row.getLocationOnScreen(loc)
        val k = quickScale()
        val x = (rawX - loc[0]) / k
        val y = (rawY - loc[1]) / k
        if (x < 0 || x > row.width || y < -dp(24) || y > row.height + dp(24)) return
        val to = row.indexAt(x, y).coerceIn(0, count - 1)
        reorderQuick(id) { to }
    }

    /** 快捷发送那排缩放了多少：横条跟着语音条缩放；竖条在弹出块里，不缩放 */
    private fun quickScale(): Float = if (voice.vertical) 1f else voice.bar.scaleX.takeIf { it > 0f } ?: 1f

    /** 打完按回车的常用文本，名字下面标一行小字「⏎ 带回车」 */
    private fun quickLabelTile(id: String, label: String): TextView =
        if (keyEditorEntry("editableText", id)?.optBoolean("enter") == true) WorkbenchTiles.noted(context, label, "⏎ 带回车")
        else WorkbenchTiles.tile(context, label)

    /**
     * 按住一条：改 / 往前挪 / 挪到最前 / 删除（删除只给常用文本：热键之类的到「调整布局」里拿掉）。
     * 菜单里每一项点的时候再按 id 现找（菜单开着时电脑可能推来了新顺序、改了这条），不用打开时记下的下标、内容
     */
    private fun showQuickMenu(id: String) {
        val label = officialButtons[id]?.label ?: return host.notice("这个按键在电脑上已经删掉了")
        val isText = keyEditorEntry("editableText", id) != null
        val isKey = !isText && keyEditorEntry("editable", id) != null
        val index = layout.quickText.indexOf(WorkbenchRef.Button(id))
        val items = ArrayList<Pair<CharSequence, () -> Unit>>()
        if (isText || isKey) items += "改" to { editQuick(id) }
        if (index > 0) {
            items += "往前挪" to { reorderQuick(id) { it - 1 } }
            items += "挪到最前" to { reorderQuick(id) { 0 } }
        }
        if (isText) {
            val delete = SpannableString("删除").apply {
                setSpan(ForegroundColorSpan(0xFFE5362B.toInt()), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            items += delete to { deleteQuick(id) }
        }
        if (items.isEmpty()) return host.notice("「$label」是电脑上配的按键：要挪、要拿掉到侧栏「调整布局」里")
        AlertDialog.Builder(context).setTitle("「$label」")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun editQuick(id: String) {
        val label = officialButtons[id]?.label ?: return host.notice("这个按键在电脑上已经删掉了")
        val text = keyEditorEntry("editableText", id)
        val key = keyEditorEntry("editable", id)
        when {
            text != null -> HotkeyDialogs.editText(context, text, profileId, "改快捷发送") { sendQuickRequest(it, "「$label」改好了") }
            key != null -> HotkeyDialogs.editKey(context, key, profileId) { sendQuickRequest(it, "「$label」改好了") }
            else -> host.notice("「$label」在电脑上变了，这里改不了")
        }
    }

    private fun deleteQuick(id: String) {
        val label = officialButtons[id]?.label ?: return host.notice("这个按键在电脑上已经删掉了")
        val text = keyEditorEntry("editableText", id) ?: return host.notice("「$label」在电脑上变了，这里删不了")
        HotkeyDialogs.deleteText(context, text, keyEditor, profileId) { sendQuickRequest(it, "已删掉「$label」") }
    }

    /** 「＋ 新建」：不用进调整布局。电脑建好按键后自己放到这一排最后（请求里带 workbenchRow） */
    private fun createQuickText() {
        // 电脑不认工作台布局：按键能建，但放不进这一排
        if (!layoutSupported) return host.notice("电脑上的蒜鸟要先更新，才能用快捷发送")
        if (shown.quickText.size >= WorkbenchLayoutV3.MAX_ROW) {
            return host.notice("快捷发送最多 ${WorkbenchLayoutV3.MAX_ROW} 条：先删掉几条再加")
        }
        HotkeyDialogs.createText(context, keyEditor, profileId, title = "新建快捷发送", workbenchRow = "quickText") { request ->
            sendQuickRequest(request, "「${request.optString("label")}」存好了")
        }
    }

    private fun sendQuickRequest(request: JSONObject, doneNotice: String) {
        if (!sendToPc(request)) return
        quickRequests[request.optString("requestId")] = doneNotice
        host.notice("已发给电脑，等它保存")
    }

    /**
     * 往前挪 / 挪到最前 / 拖到别的格子：不进调整布局，直接把挪好的布局交给电脑；电脑确认了推回来才变（跟「完成」一样）。
     * 按 id 现找它在第几个（审查 #7），to：现在的位置 → 挪到第几个
     */
    private fun reorderQuick(id: String, to: (Int) -> Int) {
        if (!layoutSupported) return host.notice("电脑上的蒜鸟要先更新，才能在这里挪顺序")
        val from = layout.quickText.indexOf(WorkbenchRef.Button(id))
        if (from < 0) return host.notice("这一条已经不在快捷发送里了")
        val next = layout.moveWithin(Container.QUICK, from, to(from).coerceIn(0, layout.quickText.size - 1))
        if (next === layout) return
        saveLayout(next, sentNotice = null, timeoutNotice = "电脑 10 秒没确认，顺序可能没改成，可以再挪一次")
    }

    /** 电脑配置里 keyEditor 的一项（kind = editable 热键 / editableText 常用文本），没有返回 null */
    internal fun keyEditorEntry(kind: String, id: String): JSONObject? {
        val list = keyEditor.optJSONArray(kind) ?: return null
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.firstOrNull { it.optString("id") == id }
    }

    /** 「打完按回车」的常用文本按键 id（快捷发送上标「⏎ 带回车」的那些） */
    private fun enterIds(editor: JSONObject): Set<String> {
        val list = editor.optJSONArray("editableText") ?: return emptySet()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { it.optBoolean("enter") }.map { it.optString("id") }.toSet()
    }

    /** 一排按钮上面加一行小标题（调整布局时分清语音条下面的两排） */
    private fun captioned(title: String, content: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(caption(title).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL },
            LinearLayout.LayoutParams(-1, dp(20)))
        addView(content, LinearLayout.LayoutParams(-1, -2))
    }

    private fun caption(text: String) = TextView(context).apply {
        this.text = text
        textSize = 10f
        typeface = Ui.font(context, 800)
        setTextColor(palette.sub)
        gravity = Gravity.CENTER
    }

    /** 键鼠条：左键 / 按住 / 右键 / 滚↑ / 滚↓ / 按键▾；竖条（v3.3 U8）时竖着排，按键区从条旁边弹出来 */
    private fun buildKeymouse() {
        keymouse.vertical = vertical
        keymouse.body.removeAllViews()
        val tiles = ArrayList<TextView>()
        fun add(label: String, action: () -> Unit): TextView = WorkbenchTiles.tile(context, label).also { t ->
            t.setOnClickListener { if (!editing) action() }
            tiles += t
        }
        add("左键") { host.leftClick(); consumeModifiers() }
        holdButton = add("按住") { toggleHold() }
        add("右键") { host.rightClick(); consumeModifiers() }
        add("滚↑") { host.scroll(1) }
        add("滚↓") { host.scroll(-1) }
        add("按键▾") { toggleExtra("keymouse") }
        if (vertical) {
            keymouse.body.addView(verticalColumn(tiles), LinearLayout.LayoutParams(-1, -2))
        } else {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            tiles.forEach { row.addView(it, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) }) }
            keymouse.body.addView(row, LinearLayout.LayoutParams(-1, dp(42)))
        }
        keymouse.extra.removeAllViews()
        val mods = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        modButtons.clear()
        for (key in ModifierLatch.KEYS) {
            val b = WorkbenchTiles.tile(context, ModifierLatch.label(key)).apply {
                setOnClickListener { if (!editing) toggleModifier(key) }
            }
            modButtons[key] = b
            mods.addView(b, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4) })
        }
        keymouse.extra.addView(mods, LinearLayout.LayoutParams(-1, dp(36)).apply { bottomMargin = dp(5) })
        // 竖条的弹出块一排放四个（跟语音条的弹出块一样宽）
        keymouse.extra.addView(slotRow(Container.KEYMOUSE, columns = if (vertical) 4 else 6), LinearLayout.LayoutParams(-1, -2))
        renderHold()
        renderModifiers()
    }

    /** 一排可调整的按钮：调整布局时每个带「−」，能拖着排顺序，最后多一个「＋」 */
    private fun slotRow(container: Container, columns: Int): FlowRow {
        val row = FlowRow(context, columns, dp(42), dp(4))
        shown.items(container).forEachIndexed { i, ref ->
            if (ref != null) row.addView(slotTile(ref, Slot(container, i), row))
        }
        val e = editor
        if (e != null) row.addView(WorkbenchTiles.plus(context, "＋ 加").apply {
            setOnClickListener { e.showLibrary(Slot(container, shown.items(container).size)) }
        })
        return row
    }

    private fun slotTile(ref: WorkbenchRef, slot: Slot, row: FlowRow?): View {
        val (label, glyph) = describeRef(ref)
        val tile = if (slot.container == Container.QUICK && ref is WorkbenchRef.Button) quickLabelTile(ref.id, label)
            else WorkbenchTiles.tile(context, label, glyph)
        val e = editor
        if (e == null) {
            // 侧栏「我的按钮」：做完就收起侧栏（v3.3 U1）
            bindAction(tile, ref, if (slot.container == Container.RAIL) ({ closeRailAfterTap() }) else null)
            return tile
        }
        val wrapped = WorkbenchTiles.withBadge(tile, "−") { e.remove(slot) }
        editDrag(wrapped, onTap = { e.showOptions(slot) }) { rawX, rawY ->
            if (row == null) return@editDrag
            val loc = IntArray(2)
            row.getLocationOnScreen(loc)
            val inside = rawX >= loc[0] && rawX <= loc[0] + row.width &&
                rawY >= loc[1] - dp(24) && rawY <= loc[1] + row.height + dp(24)
            if (inside) {
                val to = row.indexAt(rawX - loc[0], rawY - loc[1]).coerceAtMost(shown.items(slot.container).size - 1)
                e.moveWithin(slot.container, slot.index, to)
            }
        }
        return wrapped
    }

    internal fun describeRef(ref: WorkbenchRef): Pair<String, String?> = when (ref) {
        is WorkbenchRef.Fn ->
            if (ref.id == "pointer" && interaction.pointerMode == ScreenView.InputMode.PRECISE) "自然触控" to "↩"
            else WorkbenchCatalog.function(ref.id)?.let { it.label to it.glyph } ?: (ref.id to null)
        is WorkbenchRef.Key -> WorkbenchCatalog.key(ref.name)?.let { it.label to it.glyph } ?: (ref.name to null)
        is WorkbenchRef.Button -> (officialButtons[ref.id]?.label ?: "失效的按键") to null
    }

    private fun isOn(ref: WorkbenchRef): Boolean = when (ref) {
        WorkbenchRef.Fn("voice") -> session.tool(isLandscape, "voice").state != WorkbenchSession.State.HIDDEN
        WorkbenchRef.Fn("keymouse") -> session.tool(isLandscape, "keymouse").state != WorkbenchSession.State.HIDDEN
        WorkbenchRef.Fn("pointer") -> interaction.pointerMode == ScreenView.InputMode.PRECISE
        else -> false
    }

    // ───────── 悬浮球 ─────────

    private fun openRing() {
        if (session.railOpen(isLandscape)) setRailOpen(false)
        // 竖条的弹出块比那一圈高：先收回，不然压住圈里的按钮
        if (!editing) closePop()
        val e = editor
        val views: List<View> = if (e == null) {
            shown.ball.filterNotNull().map { ref ->
                val (label, glyph) = describeRef(ref)
                ball.circle(label, glyph, isOn(ref)) {
                    ball.close()
                    perform(ref)
                }
            }
        } else (0 until WorkbenchLayoutV3.BALL_SLOTS).map { i ->
            val slot = Slot(Container.BALL, i)
            val ref = shown.ball[i]
            if (ref == null) WorkbenchTiles.plus(context).apply {
                background = Ui.round(Ui.alpha(palette.tile, .6f), dp(29).toFloat(), dp(2), palette.sub)
                setOnClickListener { e.showLibrary(slot) }
            } else {
                val (label, glyph) = describeRef(ref)
                val fixed = shown.isFixed(slot)
                val circle = ball.circle(label, glyph, false) {}
                circle.setOnClickListener(null)
                circle.isClickable = false
                val wrapped = WorkbenchTiles.withBadge(circle, if (fixed) "锁" else "−") { if (!fixed) e.remove(slot) }
                editDrag(wrapped, onTap = { e.showOptions(slot) }) { rawX, rawY ->
                    val target = ball.nearest(rawX, rawY)
                    if (target >= 0 && target != i) e.swapBall(i, target)
                }
                wrapped
            }
        }
        ball.open(views, modal = e == null)
    }

    private fun onBallDragged() {
        if (width <= 0 || height <= 0) return
        // 球停的地方（一圈开着时球可能临时待在圈中间，那不算挪过）
        val x = ball.restX / width
        val y = ball.restY / height
        val e = editor
        if (e != null) e.moveBall(isLandscape, x.toDouble(), y.toDouble()) else session.setBall(isLandscape, x, y)
    }

    // ───────── 侧栏 ─────────

    /**
     * 侧栏（v3.3 U1，用户 2026-11-12 定的「塞进空白」）：不再把画面往左顶（顶过去以后操作语音条会碰到画面），也不盖画面。
     * 横屏画面完整显示时，一列窄按钮塞进画面右边的空白；空白不够、开着「铺满屏幕」才盖在画面右边。竖屏放到画面下面。
     * 怎么摆见 RailFit；画面变了（转屏、第一帧量出桌面尺寸、铺满屏幕开关）由 updateDock 重算。
     */
    private fun applyRail() {
        val land = isLandscape
        rail.setOpen(editing || session.railOpen(land))
        val e = editor
        val groups = settingTiles()
        val mine = shown.rail.mapIndexed { i, ref -> slotTile(ref, Slot(Container.RAIL, i), null) }
        val plus = if (e != null) WorkbenchTiles.plus(context, "＋ 加").apply {
            setOnClickListener { e.showLibrary(Slot(Container.RAIL, shown.rail.size)) }
        } else null
        val hasMine = mine.isNotEmpty() || plus != null
        railSections = groups.map { it.size } + if (hasMine) listOf(mine.size + (if (plus != null) 1 else 0)) else emptyList()
        railMine = hasMine
        val plan = railPlan()
        railPlan = plan
        rail.setContent(groups, mine, plus, plan)
        if (width > 0 && height > 0) rail.keepBelow = if (land) 0 else host.pictureBottom()
    }

    /** 侧栏现在该怎么摆：竖屏一列放画面下面；横屏按画面右边的空白算（RailFit） */
    private fun railPlan(): RailFit.Plan {
        if (width <= 0 || height <= 0 || !isLandscape) return RailFit.PORTRAIT
        val right = host.pictureRight().coerceIn(0, width)
        return RailFit.plan((width - right) / d, height / d, host.isFill(), railSections, railMine)
    }

    /** 画面的位置、大小变了：空白宽窄变了才重做侧栏的按钮，不然只把竖屏的侧栏挪到画面下面 */
    private fun updateDock() {
        if (width <= 0 || height <= 0) return
        if (railPlan() != railPlan) applyRail() else rail.keepBelow = if (isLandscape) 0 else host.pictureBottom()
    }

    /** 画面的位置、大小变了（转屏、第一帧量出桌面尺寸、铺满屏幕开关）：MainActivity 调，侧栏按新的空白重新摆 */
    fun onPictureChanged() = updateDock()

    private fun setRailOpen(open: Boolean) {
        session.setRailOpen(isLandscape, open)
        if (open) {
            ball.close()
            closePop()
        }
        applyRail()
    }

    /** 侧栏里点了一项、做完了：收起侧栏（v3.3 U1）。调整布局时侧栏一直开着；点的是「返回主页」已经退出全屏了 */
    private fun closeRailAfterTap() {
        if (editing || !isAttachedToWindow || !session.railOpen(isLandscape)) return
        setRailOpen(false)
    }

    private fun settingTiles(): List<List<View>> {
        fun t(label: String, on: Boolean = false, action: () -> Unit): View =
            WorkbenchTiles.tile(context, label, on = on).apply {
                setOnClickListener {
                    if (editing) return@setOnClickListener
                    action()
                    closeRailAfterTap()
                }
            }
        return listOf(
            listOf(
                t("只看画面", interaction.pointerMode == ScreenView.InputMode.VIEW) { toggleViewOnly() },
                t("铺满屏幕", host.isFill()) { host.toggleFill() },
                t("锁定方向", host.isRotationLocked()) { host.toggleRotationLock() },
                t("闲时半透明", session.idleFade) { toggleIdleFade() },
                t("竖条", vertical) { toggleVerticalBars() },
            ),
            listOf(
                t("恢复大小") { host.resetZoom() },
                t("调整布局") { startEditing() },
                t("鼠标样式") { showCursorAppearance() },
                t("操作说明") { showGuide() },
                t("语音设置") { showVoiceSettings() },
            ),
            listOf(t("返回主页") { host.close() }),
        )
    }

    // ───────── 位置：转屏只挪，不重做 ─────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val first = oldw <= 0 || oldh <= 0
        val rotated = !first && (w > h) != (oldw > oldh)
        if (rotated) ball.close()
        post {
            // 第一次量出尺寸时也要按方向重排侧栏：构造时还不知道是横屏还是竖屏
            if (rotated || first) {
                editor?.onRotated()
                applyRail() // 横屏塞进画面右边的空白、竖屏一列放画面下面
            } else updateDock() // 系统栏出没、键盘：画面的位置可能变了
            applyAll(animate = !first)
            // 调整布局时那一圈应该一直开着：转屏时先关了（按钮位置要按新方向算），排好以后按新方向重新打开（审查 #16）
            if (editing && !ball.isOpen) openRing()
        }
    }

    private fun applyAll(animate: Boolean) {
        if (width <= 0 || height <= 0) return
        applyTool("voice", animate, place = false)
        applyTool("keymouse", animate, place = false)
        placeBars(animate)
        applyBall(animate)
    }

    private fun bar(name: String) = if (name == "voice") voice else keymouse

    /** place = false：调用方接着自己 placeBars（两条一起摆，免得摆两遍） */
    private fun applyTool(name: String, animate: Boolean, place: Boolean = true) {
        if (width <= 0 || height <= 0) return
        val land = isLandscape
        val b = bar(name)
        val geo = shown.geometry(land).let { if (name == "voice") it.voice else it.keymouse }
        val s = session.tool(land, name)
        b.editing = editing
        b.setWidth(if (b.vertical) (FloatingBar.VERTICAL_WIDTH_DP * d).toInt() else (geo.w * width).toInt().coerceAtMost(width - dp(8)))
        b.setScale(geo.k.toFloat())
        // 竖条的展开区是弹出块：开没开只在这次全屏里记着（popName）；调整布局时两条的都摆出来
        b.setExtraVisible(when {
            editing -> true
            b.vertical -> popName == name
            else -> s.extra
        })
        if (name == "voice") renderVoiceRows() // 横屏、竖屏各记着开的是哪一排
        b.show(if (editing) WorkbenchSession.State.OPEN else s.state)
        val (x, y) = barHome(name)
        move(b.pill, (s.pillX ?: x) * width, (s.pillY ?: y) * height, animate)
        renderPopScrim()
        if (place) placeBars(animate)
    }

    /** 条该在的地方（占屏幕的比例）：调整布局时是布局里的默认位置，平时是拖到的位置（没拖过用默认） */
    private fun barHome(name: String): Pair<Float, Float> {
        val geo = shown.geometry(isLandscape).let { if (name == "voice") it.voice else it.keymouse }
        val s = session.tool(isLandscape, name)
        val x = if (editing) geo.x.toFloat() else s.x ?: geo.x.toFloat()
        val y = if (editing) geo.y.toFloat() else s.y ?: geo.y.toFloat()
        return x to y
    }

    /**
     * 按条现在的内容量一下有多高（还没排版、换过竖条、开关过展开区时 height 还是旧的）。
     * 跟父容器排版时量的条件一样（宽固定、高最多一屏），接着排版时不用再量一遍
     */
    private fun measuredBarHeight(b: FloatingBar): Int {
        val w = b.bar.layoutParams?.width ?: return b.bar.height
        if (w <= 0 || height <= 0) return b.bar.height
        b.bar.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        return b.bar.measuredHeight
    }

    /** 条没让开时显示在哪（像素，算上缩放，拉回屏幕里）；条没显示返回 null */
    private fun barBox(name: String): BarStacking.Box? {
        val b = bar(name)
        if (b.bar.visibility != View.VISIBLE) return null
        val lpw = b.bar.layoutParams?.width ?: return null
        if (lpw <= 0) return null
        val w = lpw * b.bar.scaleX
        val h = measuredBarHeight(b) * b.bar.scaleY
        val (rx, ry) = barHome(name)
        val m = dp(4).toFloat()
        return BarStacking.Box((rx * width).coerceIn(m, (width - m - w).coerceAtLeast(m)),
            (ry * height).coerceIn(m, (height - m - h).coerceAtLeast(m)), w, h)
    }

    /**
     * 把展开的条摆到该在的地方；两条都展开又叠在一起时，把上面那条（顶边更高的；一样高就后展开的）挪开（v3.3 U2）：
     * 横条挪到另一条正上方（隔 6dp），放不下放正下方；竖条挪到另一条左边或右边（BarStacking）。
     * 只是显示时让开：存着的位置不变，用户之后拖哪条才记哪条的新位置。
     * 展开、收起、展开区开关、转屏、拖完松手、配置变了重建都会走到这里
     */
    private fun placeBars(animate: Boolean) {
        if (width <= 0 || height <= 0) return
        removeCallbacks(barsTask)
        barHomes.clear()
        for (name in NAMES) barBox(name)?.let { barHomes[name] = it }
        val shownAt = HashMap(barHomes)
        val v = barHomes["voice"]
        val k = barHomes["keymouse"]
        if (v != null && k != null) {
            val moveVoice = when {
                v.y < k.y - 0.5f -> true
                k.y < v.y - 0.5f -> false
                else -> lastExpanded == "voice"
            }
            val (fixed, moving) = if (moveVoice) k to v else v to k
            shownAt[if (moveVoice) "voice" else "keymouse"] = BarStacking.resolve(fixed, moving,
                width.toFloat(), height.toFloat(), dp(6).toFloat(), dp(4).toFloat(), sideBySide = vertical)
        }
        barShown.clear()
        barShown.putAll(shownAt)
        for ((name, box) in shownAt) {
            slide(bar(name).bar, box.x, box.y, animate)
            placePop(name, box)
        }
    }

    /** 条的内容高度后来才变（排版以后才知道）：下一帧重新摆一次 */
    private fun requestPlaceBars() {
        removeCallbacks(barsTask)
        post(barsTask)
    }

    /** 把条挪到 (x, y)：已经在那儿、或者正往那儿挪就不打断（摆的时候会一再算到同一个位置） */
    private fun slide(v: View, x: Float, y: Float, animate: Boolean) {
        val last = slides[v]
        val now = SystemClock.uptimeMillis()
        val sameTarget = last != null && last.x == x && last.y == y
        val there = abs(v.translationX - x) < 0.5f && abs(v.translationY - y) < 0.5f
        if ((sameTarget && now - (last?.at ?: 0L) < 320L) || (there && (last == null || sameTarget))) return
        v.animate().cancel()
        if (animate && v.visibility == View.VISIBLE) {
            slides[v] = Slide(x, y, now)
            v.animate().translationX(x).translationY(y).setDuration(280).setInterpolator(Ui.SOFT_SPRING).start()
        } else {
            slides.remove(v)
            v.translationX = x
            v.translationY = y
        }
    }

    private fun applyBall(animate: Boolean) {
        val g = shown.geometry(isLandscape)
        val pos = if (editing) null else session.ball(isLandscape)
        ball.moveTo((pos?.first ?: g.ballX.toFloat()) * width, (pos?.second ?: g.ballY.toFloat()) * height, animate)
    }

    private fun move(v: View, x: Float, y: Float, animate: Boolean) {
        v.animate().cancel()
        if (animate && v.visibility == View.VISIBLE) {
            v.animate().translationX(x).translationY(y).setDuration(280).setInterpolator(Ui.SOFT_SPRING)
                .withEndAction { clamp(v) }.start()
        } else {
            v.translationX = x
            v.translationY = y
            v.post { clamp(v) }
        }
    }

    /** 别跑出屏幕（算上缩放） */
    private fun clamp(v: View) {
        if (width <= 0 || height <= 0 || v.width <= 0) return
        val w = v.width * v.scaleX
        val h = v.height * v.scaleY
        val margin = dp(4).toFloat()
        v.translationX = v.translationX.coerceIn(margin, (width - margin - w).coerceAtLeast(margin))
        v.translationY = v.translationY.coerceIn(margin, (height - margin - h).coerceAtLeast(margin))
    }

    private fun wireBars() {
        for (name in NAMES) {
            val b = bar(name)
            b.clampBar = {
                clamp(b.bar)
                placePop(name) // 竖条拖着走时弹出块跟着
            }
            b.clampPill = { clamp(b.pill) }
            b.onCollapse = { setToolState(name, WorkbenchSession.State.COLLAPSED) }
            b.onExpand = { setToolState(name, WorkbenchSession.State.OPEN) }
            b.onBarDragged = {
                val x = b.bar.translationX / width
                val y = b.bar.translationY / height
                val e = editor
                if (e != null) e.moveTool(isLandscape, name, x.toDouble(), y.toDouble())
                else session.setTool(isLandscape, name, session.tool(isLandscape, name).copy(x = x, y = y))
                // 拖完松手：叠在另一条上就让开（v3.3 U2）
                placeBars(animate = true)
            }
            b.onPillDragged = {
                session.setTool(isLandscape, name, session.tool(isLandscape, name)
                    .copy(pillX = b.pill.translationX / width, pillY = b.pill.translationY / height))
            }
            b.onScaled = { k -> editor?.scaleTool(isLandscape, name, k.toDouble()) }
            // 条、弹出块的大小排版以后才变（内容刚换、字体刚加载好）：重新摆
            b.bar.addOnLayoutChangeListener { _, l, t, r, bottom, ol, ot, or, ob ->
                if (r - l != or - ol || bottom - t != ob - ot) requestPlaceBars()
            }
            b.pop.addOnLayoutChangeListener { _, l, t, r, bottom, ol, ot, or, ob ->
                if (r - l != or - ol || bottom - t != ob - ot) placePop(name, barShown[name])
            }
        }
    }

    private fun setToolState(name: String, state: WorkbenchSession.State) {
        if (width <= 0 || height <= 0) return
        val land = isLandscape
        val b = bar(name)
        var t = session.tool(land, name)
        if (state == WorkbenchSession.State.COLLAPSED && b.bar.visibility == View.VISIBLE && b.bar.height > 0) {
            // 小把手出现在条的左下角：按条没让开时的位置算（让开只是显示），展开再收起还回到原来那个小把手的地方
            val home = barHomes[name]
            val left = home?.x ?: b.bar.translationX
            val bottom = home?.bottom ?: (b.bar.translationY + b.bar.height * b.bar.scaleY)
            t = t.copy(pillX = left / width, pillY = ((bottom - dp(36)) / height).coerceIn(0f, 1f))
        }
        if (state == WorkbenchSession.State.OPEN && b.pill.visibility == View.VISIBLE) {
            // 从小把手展开：条的左下角对齐小把手。高度按现在的内容量（换过竖条、开关过展开区，上次的高度不准）
            val barH = measuredBarHeight(b) * b.bar.scaleY
            if (barH > 0f) t = t.copy(x = b.pill.translationX / width, y = ((b.pill.translationY + dp(36) - barH) / height).coerceIn(0f, 1f))
        }
        if (state == WorkbenchSession.State.OPEN) lastExpanded = name
        // 条收起、隐藏了：它的弹出块也收回
        if (state != WorkbenchSession.State.OPEN && popName == name) {
            popName = null
            popRow = null
        }
        session.setTool(land, name, t.copy(state = state))
        if (name == "voice" && state == WorkbenchSession.State.HIDDEN) mic.stopListening()
        applyTool(name, animate = false)
    }

    private fun toggleVisible(name: String) {
        val hidden = session.tool(isLandscape, name).state == WorkbenchSession.State.HIDDEN
        setToolState(name, if (hidden) WorkbenchSession.State.OPEN else WorkbenchSession.State.HIDDEN)
    }

    private fun toggleExtra(name: String) {
        if (vertical) {
            // 竖条：按键区从条旁边弹出来（只在这次全屏里记着），再点一下收回
            if (popName == name) closePop() else openPop(name)
            return
        }
        val t = session.tool(isLandscape, name)
        session.setTool(isLandscape, name, t.copy(extra = !t.extra))
        applyTool(name, animate = false)
    }

    /** 双指长按 / 三指点：打开键鼠条并展开按键（原来的快捷键弹窗不要了；竖条时按键区从条旁边弹出来） */
    fun openKeys() {
        if (editing) return
        val t = session.tool(isLandscape, "keymouse")
        if (t.state != WorkbenchSession.State.OPEN) lastExpanded = "keymouse"
        if (vertical) {
            session.setTool(isLandscape, "keymouse", t.copy(state = WorkbenchSession.State.OPEN))
            openPop("keymouse")
            return
        }
        session.setTool(isLandscape, "keymouse", t.copy(state = WorkbenchSession.State.OPEN, extra = true))
        applyTool("keymouse", animate = false)
    }

    /**
     * 双击悬浮球（v3.3 U6）：两条里有一条展开着，就把没隐藏的都收成小把手；都收着就都展开。
     * 被悬浮球里「语音」「键鼠」隐藏的那条不动。调整布局时两条一直展开着，不管
     */
    private fun toggleBothBars() {
        if (editing) return
        val land = isLandscape
        val names = NAMES.filter { session.tool(land, it).state != WorkbenchSession.State.HIDDEN }
        if (names.isEmpty()) return host.notice("语音条、键鼠条都隐藏了：点开悬浮球，点「语音」「键鼠」显示")
        val anyOpen = names.any { session.tool(land, it).state == WorkbenchSession.State.OPEN }
        val target = if (anyOpen) WorkbenchSession.State.COLLAPSED else WorkbenchSession.State.OPEN
        for (name in names) if (session.tool(land, name).state != target) setToolState(name, target)
    }

    /** 侧栏「闲时半透明」（v3.3 U7）：开着时两条、小把手 3 秒没碰就变淡，一碰就恢复 */
    private fun toggleIdleFade() {
        session.idleFade = !session.idleFade
        applyIdleFade()
        host.notice(if (session.idleFade) "闲时半透明：语音条、键鼠条 3 秒没碰就变淡，一碰就恢复" else "闲时半透明关了")
    }

    private fun applyIdleFade() {
        voice.fadeWhenIdle = session.idleFade
        keymouse.fadeWhenIdle = session.idleFade
    }

    /** 侧栏「竖条」（v3.3 U8）：两条一起换成竖着的窄条，再点一下换回横条。位置、收起展开照旧 */
    private fun toggleVerticalBars() {
        session.verticalBars = !session.verticalBars
        popName = null
        popRow = null
        rebuildContents()
        applyAll(animate = false)
        host.notice(if (vertical) "竖条：两条竖着排，「编辑▾」「快捷▾」「按键▾」从条旁边弹出来，点别处收回" else "换回横条了")
    }

    // ───────── 按下去做什么 ─────────

    private fun perform(ref: WorkbenchRef) {
        when (ref) {
            is WorkbenchRef.Fn -> when (ref.id) {
                "voice" -> toggleVisible("voice")
                "keymouse" -> toggleVisible("keymouse")
                "pointer" -> togglePrecision()
                "taskSwitch" -> startTaskSwitch()
                "sendFile" -> host.pickFiles()
                else -> WorkbenchCatalog.CAPTURE_KEYS[ref.id]?.let { (key, mods) ->
                    startCapture(key, mods, WorkbenchCatalog.function(ref.id)?.label ?: "截图")
                }
            }
            is WorkbenchRef.Key -> {
                host.sendKey(ref.name, emptyList())
                consumeModifiers()
            }
            is WorkbenchRef.Button -> sendOfficial(ref.id, "click")
        }
    }

    /** after：做完以后再做的事（侧栏里的按钮做完收起侧栏）；双击、长按的按键等判定出来、发出去以后才算做完 */
    private fun bindAction(tile: View, ref: WorkbenchRef, after: (() -> Unit)? = null) {
        val info = (ref as? WorkbenchRef.Button)?.let { officialButtons[it.id] }
        if (info != null && (info.hasDouble || info.hasLong)) bindTriggers(tile, info, after)
        else tile.setOnClickListener {
            perform(ref)
            after?.invoke()
        }
    }

    /** 电脑上配了双击、长按的按键：按 KeyButton 一样的规则判定 */
    private fun bindTriggers(tile: View, info: OfficialButton, after: (() -> Unit)? = null) {
        val triggers = TapTriggers(info.hasDouble, info.hasLong, doubleMs, longMs)
        val fire = { trigger: String ->
            sendOfficial(info.id, trigger)
            after?.invoke()
        }
        val tick = Runnable { triggers.tick(SystemClock.uptimeMillis())?.let(fire) }
        tile.setOnTouchListener { v, e ->
            val now = SystemClock.uptimeMillis()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    triggers.down(now)
                    if (info.hasLong) v.postDelayed(tick, longMs)
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    v.removeCallbacks(tick)
                    triggers.up(now)?.let(fire)
                    if (info.hasDouble) v.postDelayed(tick, doubleMs + 1)
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.removeCallbacks(tick)
                    triggers.cancel()
                }
            }
            true
        }
    }

    /** sentNotice：真发出去了再提示一句（快捷发送用：「已发：收到」） */
    private fun sendOfficial(id: String, trigger: String, sentNotice: String? = null) {
        val info = officialButtons[id] ?: return host.notice("这个按键在电脑上已经删掉了，可以在调整布局里换掉")
        val send = {
            if (host.sendButton(id, trigger) && sentNotice != null) host.notice(sentNotice)
            consumeModifiers()
        }
        if (info.confirm && trigger == "click") HotkeyDialogs.confirmSend(context, info.label, send)
        else send()
    }

    // ───────── 修饰键（Ctrl / Shift / Alt / Win） ─────────

    private fun toggleModifier(key: String) {
        when (latch.cycle(key)) {
            true -> if (!host.sendModifier(key, true)) {
                // 按下没发出去（修饰键只走局域网）：退回「关」，不显示成按着
                latch.drop(key)
                host.notice("${ModifierLatch.label(key)} 要局域网连着才能按住")
            }
            false -> if (!sendModifierUps(listOf(key))) host.notice("没连上电脑：连上后马上让电脑松开 ${ModifierLatch.label(key)}")
            null -> Unit
        }
        renderModifiers()
        restartModifierTimeout()
    }

    /** 电脑画面上点了一下、按了一个键：「一次」的修饰键用掉了（MainActivity 在画面点击松手时也调） */
    fun onRemoteInput() = consumeModifiers()

    private fun consumeModifiers() {
        sendModifierUps(latch.consumeOnce())
        renderModifiers()
        restartModifierTimeout()
    }

    /** 全部变「关」并让电脑松开。返回 false = 一条连接都没有，电脑那边要等连上才松开 */
    private fun releaseModifiers(): Boolean {
        val released = sendModifierUps(latch.releaseAll())
        renderModifiers()
        removeCallbacks(modifierTimeout)
        return released
    }

    /**
     * 发松开。修饰键只走局域网，发不出去不能当成松开了：电脑上 Ctrl 会一直按着，电脑键盘上打的字全变成 Ctrl+字母（审查 #1）。
     * 这时改发「全部松开」（蓝牙也认），一条连接都没有就等连上再发。返回 false = 电脑现在还没松开
     */
    private fun sendModifierUps(keys: List<String>): Boolean {
        var lost = false
        for (key in keys) if (!host.sendModifier(key, false)) lost = true
        return !lost || host.releaseEverything()
    }

    /**
     * 手机这边把按着的都变回「松开」，不往电脑发：修饰键、「按住」、切换应用的条。返回刚才有没有按着的。
     * 用在：电脑已经全部松开了（keysReleased），或者局域网断了、MainActivity 另外发「全部松开」
     */
    fun dropHeldState(): Boolean {
        val mods = latch.releaseAll().isNotEmpty()
        removeCallbacks(modifierTimeout)
        renderModifiers()
        val held = holdOn
        setMouseHeld(false)
        val switching = closeTaskSwitch()
        return mods || held || switching
    }

    /**
     * 电脑把按着的键全松开了（发来 keysReleased：改了配置、暂停、换方案、看门狗、局域网断开……，v3.2 K4）：
     * 修饰键都变「关」、「松开」变回「按住」、切换应用的条收起，不再发松开。真有按着的才说一声，平时不打扰
     */
    fun onKeysReleased() {
        if (dropHeldState()) host.notice("电脑上按着的键都松开了")
    }

    /** App 退到后台：按着的 Ctrl 等、窗口切换按着的 Alt 都松开，回来时电脑上不能还按着 */
    fun onPaused() {
        releaseModifiers()
        finishTaskSwitch(false)
    }

    private fun restartModifierTimeout() {
        removeCallbacks(modifierTimeout)
        if (latch.anyActive()) postDelayed(modifierTimeout, 10_000L)
    }

    private fun renderModifiers() {
        for ((key, b) in modButtons) {
            when (latch.state(key)) {
                ModifierLatch.State.OFF -> {
                    b.text = ModifierLatch.label(key)
                    WorkbenchTiles.setOn(b, false)
                }
                ModifierLatch.State.ONCE -> {
                    b.text = ModifierLatch.label(key) + " ·一次"
                    WorkbenchTiles.setArmed(b)
                }
                ModifierLatch.State.LOCKED -> {
                    b.text = ModifierLatch.label(key) + " ·锁"
                    WorkbenchTiles.setOn(b, true)
                }
            }
        }
        val text = latch.chipText()
        modifierChip.text = text.orEmpty()
        modifierChip.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // ───────── 鼠标模式、按住 ─────────

    private fun togglePrecision() = setMode(interaction.togglePrecision())
    private fun toggleViewOnly() = setMode(interaction.toggleViewOnly())

    private fun setMode(mode: ScreenView.InputMode, announce: Boolean = true) {
        if (!interaction.setPointerMode(mode)) return
        host.setInputMode(mode)
        if (mode != ScreenView.InputMode.PRECISE) setMouseHeld(false)
        // 精密鼠标要用右键、按住：键鼠条自己出来（收着）
        if (mode == ScreenView.InputMode.PRECISE &&
            session.tool(isLandscape, "keymouse").state == WorkbenchSession.State.HIDDEN) {
            setToolState("keymouse", WorkbenchSession.State.COLLAPSED)
        }
        applyRail()
        if (ball.isOpen) openRing() // 「精密鼠标」⇄「自然触控」的字跟着变
        if (announce) host.notice(when (mode) {
            ScreenView.InputMode.VIEW -> "只看画面：不会往电脑发触控；再点一次「只看画面」恢复"
            ScreenView.InputMode.DIRECT -> "自然触控：点 = 左键，滑 = 滚轮，长按再拖 = 拖动"
            ScreenView.InputMode.PRECISE -> "精密鼠标：单指移动箭头，点一下 = 左键，双指上下 = 滚轮"
        })
    }

    private fun toggleHold() {
        if (interaction.pointerMode != ScreenView.InputMode.PRECISE) {
            // 自然触控没有箭头（在画面上长按再拖就是拖动）。「按住」配合精密鼠标的箭头用：先切过去，不马上按下
            setMode(ScreenView.InputMode.PRECISE, announce = false)
            host.notice("已切到精密鼠标：先把箭头移到要拖的地方，再点「按住」")
            return
        }
        holdOn = host.toggleHold()
        renderHold()
        if (!holdOn) consumeModifiers()
    }

    fun setMouseHeld(held: Boolean) {
        holdOn = held
        renderHold()
    }

    private fun renderHold() {
        holdButton?.let {
            it.text = if (holdOn) "松开" else "按住"
            WorkbenchTiles.setOn(it, holdOn)
        }
    }

    // ───────── 切换应用、截图框选（搬自旧版） ─────────

    fun switchApp() = startTaskSwitch()

    private fun startTaskSwitch() {
        if (!taskSwitcherActive && !interaction.start(WorkbenchInteractionState.Task.WINDOW_SWITCH)) {
            host.notice("请先结束当前截图或语音")
            return
        }
        if (!taskSwitcherActive) {
            if (!host.sendTaskSwitch("begin")) {
                interaction.finish(WorkbenchInteractionState.Task.WINDOW_SWITCH)
                host.notice("没打开窗口切换，请检查电脑连接")
                return
            }
            taskSwitcherActive = true
            renderState()
            taskSwitchPanel.visibility = View.VISIBLE
            host.notice("按「下一个」连续选窗口，选好点「确定」")
        } else if (!host.sendTaskSwitch("next")) finishTaskSwitch(false)
        removeCallbacks(taskSwitchTimeout)
        postDelayed(taskSwitchTimeout, 10_000L)
    }

    private fun finishTaskSwitch(commit: Boolean) {
        if (!closeTaskSwitch()) return
        // 发不出去（局域网断了）：电脑上 Alt 还按着，改发「全部松开」（蓝牙也认；一条连接都没有就等连上再发）
        if (!host.sendTaskSwitch(if (commit) "commit" else "cancel")) host.releaseEverything()
    }

    /** 手机这边收起切换应用的条（不发给电脑）。返回刚才是不是开着 */
    private fun closeTaskSwitch(): Boolean {
        removeCallbacks(taskSwitchTimeout)
        if (!taskSwitcherActive) return false
        taskSwitcherActive = false
        interaction.finish(WorkbenchInteractionState.Task.WINDOW_SWITCH)
        renderState()
        taskSwitchPanel.visibility = View.GONE
        return true
    }

    private fun buildTaskSwitchPanel() {
        taskSwitchPanel.removeAllViews()
        fun add(label: String, weight: Float, action: () -> Unit) {
            taskSwitchPanel.addView(WorkbenchTiles.tile(context, label).apply { setOnClickListener { action() } },
                LinearLayout.LayoutParams(0, dp(39), weight).apply { marginEnd = dp(3) })
        }
        add("◀ 上一个", 1f) {
            if (taskSwitcherActive && host.sendTaskSwitch("previous")) {
                removeCallbacks(taskSwitchTimeout)
                postDelayed(taskSwitchTimeout, 10_000L)
            }
        }
        add("下一个 ▶", 1f) { startTaskSwitch() }
        add("确定 ✓", .9f) { finishTaskSwitch(true) }
        add("取消 ×", .9f) { finishTaskSwitch(false) }
    }

    private fun startCapture(key: String, modifiers: List<String>, label: String) {
        if (!interaction.start(WorkbenchInteractionState.Task.SCREEN_SELECTION)) {
            host.notice("请先结束当前的窗口切换或语音")
            return
        }
        if (host.sendKey(key, modifiers)) {
            host.armSelection()
            renderState()
            host.notice("$label：在画面上拖框选区（或点起点再点终点），Esc 取消")
        } else {
            interaction.finish(WorkbenchInteractionState.Task.SCREEN_SELECTION)
        }
        consumeModifiers()
    }

    fun onSelectionStateChanged(active: Boolean) {
        if (!active) interaction.finish(WorkbenchInteractionState.Task.SCREEN_SELECTION)
        renderState()
    }

    private fun renderState() {
        stateChip.text = when (interaction.task) {
            WorkbenchInteractionState.Task.IDLE -> ""
            WorkbenchInteractionState.Task.SCREEN_SELECTION -> "框选中 · Esc 取消"
            WorkbenchInteractionState.Task.WINDOW_SWITCH -> "选择窗口 · 确定 / 取消"
            WorkbenchInteractionState.Task.VOICE_RECORDING ->
                if (mic.isAwaitingResult()) "正在识别 · 请稍候" else "录音中 · 再点一下结束"
        }
        stateChip.visibility = if (interaction.task == WorkbenchInteractionState.Task.IDLE) View.GONE else View.VISIBLE
    }

    // ───────── 语音 ─────────

    private fun onVoiceActivityChanged(active: Boolean) {
        if (active) {
            // 录音时 Alt 不能还按着，也不能停在框选里
            if (interaction.task == WorkbenchInteractionState.Task.WINDOW_SWITCH) finishTaskSwitch(false)
            if (interaction.task == WorkbenchInteractionState.Task.SCREEN_SELECTION) {
                host.cancelSelection()
                interaction.finish(WorkbenchInteractionState.Task.SCREEN_SELECTION)
            }
            interaction.start(WorkbenchInteractionState.Task.VOICE_RECORDING)
        } else interaction.finish(WorkbenchInteractionState.Task.VOICE_RECORDING)
        renderVoicePhase()
    }

    private fun renderVoicePhase() {
        renderState()
        val recording = mic.isRecordingNow()
        val waiting = mic.isAwaitingResult()
        talkButton?.let {
            // 竖条只有 70dp 宽：字短一点
            it.text = when {
                recording -> if (vertical) "● 结束" else "● 结束录音"
                waiting -> if (vertical) "识别中…" else "正在识别…"
                else -> if (vertical) "说话" else "点按开始说话"
            }
            it.isEnabled = !waiting
            if (recording) {
                it.setTextColor(Color.WHITE)
                it.background = Ui.round(palette.red, 13 * d)
            } else WorkbenchTiles.setOn(it, false)
        }
        voice.setRecording(recording || waiting)
    }

    /**
     * 手机键盘打字：复用原版 VoiceBox 和手机输入法（九键、26 键在输入法里切），选好字点发送才发给电脑。
     * 对话框是单独的窗口，弹出输入法不会挤压后面的视频画面。
     */
    private fun showPhoneKeyboard() {
        keyboardDialog?.takeIf { it.isShowing }?.let {
            it.window?.decorView?.requestFocus()
            return
        }
        if (interaction.task == WorkbenchInteractionState.Task.SCREEN_SELECTION) {
            host.cancelSelection()
            interaction.finish(WorkbenchInteractionState.Task.SCREEN_SELECTION)
        }
        if (interaction.task == WorkbenchInteractionState.Task.WINDOW_SWITCH) finishTaskSwitch(false)
        ball.close()
        lateinit var box: VoiceBox
        box = VoiceBox(context,
            onSend = { text ->
                if (BaseInputConnection.getComposingSpanStart(box.input.text) >= 0) {
                    host.notice("请先选好拼音候选字，再点发送")
                    false
                } else host.sendText(text)
            },
            onNotice = { host.notice(it) },
        ).apply { autoSend = false }
        keyboardBox = box
        box.input.apply {
            hint = "支持拼音九键 / 26 键，选好字后点发送"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setText(keyboardDraft)
            setSelection(text.length)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { keyboardDraft = s?.toString().orEmpty() }
            })
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "键盘输入 · 九键 / 26 键在手机输入法里切"
            textSize = 12f
            setTextColor(palette.fg)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(35), 1f))
        header.addView(WorkbenchTiles.tile(context, "输入法").apply {
            setOnClickListener { context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() }
        }, LinearLayout.LayoutParams(dp(52), dp(34)).apply { marginEnd = dp(4) })
        header.addView(WorkbenchTiles.tile(context, "关闭").apply {
            setOnClickListener { keyboardDialog?.dismiss() }
        }, LinearLayout.LayoutParams(dp(46), dp(34)))
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(9))
            background = Ui.round(Ui.alpha(palette.tile, .98f), dp(16).toFloat(), Ui.hairline(context).toInt(), palette.line)
            addView(header, LinearLayout.LayoutParams(-1, dp(36)))
            addView(box, LinearLayout.LayoutParams(-1, dp(72)))
        }
        val dialog = Dialog(context).apply {
            setContentView(content)
            setOnDismissListener {
                keyboardDraft = box.input.text.toString()
                keyboardBox = null
                keyboardDialog = null
            }
        }
        keyboardDialog = dialog
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            setLayout((resources.displayMetrics.widthPixels - dp(12)).coerceIn(dp(220), dp(540)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        box.input.requestFocus()
        box.input.postDelayed({
            if (dialog.isShowing) context.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(box.input, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    // ───────── 侧栏里的设置 ─────────

    private fun showCursorAppearance() {
        val presets = arrayOf("小电脑箭头（推荐）", "标准电脑箭头", "小十字准星", "标准十字准星")
        AlertDialog.Builder(context).setTitle("精密鼠标 · 样式和大小")
            .setItems(presets) { _, selected ->
                val style = if (selected < 2) ScreenView.CursorStyle.ARROW else ScreenView.CursorStyle.CROSSHAIR
                host.setCursorAppearance(style, if (selected % 2 == 0) 0.80f else 1.0f)
                host.notice("精密鼠标的箭头换好了")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 侧栏「语音设置」：就是主页 ⋯ 菜单「语音识别」那几项（本地识别快 / 更准、系统引擎、边说边打字），全屏里换不用退出 */
    private fun showVoiceSettings() {
        // 正在录音、识别时换引擎会把这一段弄丢：先说完
        if (mic.isBusy()) return host.notice("正在录音或识别：说完再改语音设置")
        val current = host.voiceSettings()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
        }
        val group = RadioGroup(context)
        for (option in current.options) {
            group.addView(RadioButton(context).apply {
                id = View.generateViewId() // RadioGroup 靠 id 记选中的那个，加进去之前就要有
                tag = option.id
                text = SpannableStringBuilder(option.title).apply {
                    if (option.note.isNotEmpty()) {
                        append("\n")
                        val start = length
                        append(option.note)
                        setSpan(RelativeSizeSpan(0.8f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(ForegroundColorSpan(palette.sub), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                isEnabled = option.id.isNotEmpty()
                isChecked = option.id.isNotEmpty() && option.id == current.chosen
                setPadding(paddingLeft, dp(6), paddingRight, dp(6))
            })
        }
        panel.addView(group, LinearLayout.LayoutParams(-1, -2))
        val live = CheckBox(context).apply {
            text = SpannableStringBuilder("边说边打字").apply {
                append("\n")
                val start = length
                append("说一段打一段，关掉就整句发")
                setSpan(RelativeSizeSpan(0.8f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(palette.sub), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            isChecked = current.liveTyping
            setPadding(paddingLeft, dp(6), paddingRight, dp(6))
        }
        panel.addView(live, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        AlertDialog.Builder(context).setTitle("语音设置")
            .setView(ScrollView(context).apply { addView(panel) })
            .setPositiveButton("好") { _, _ ->
                val choice = group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? String ?: current.chosen
                if (choice == current.chosen && live.isChecked == current.liveTyping) return@setPositiveButton
                host.setVoiceSettings(choice, live.isChecked)
                host.notice("语音设置改好了")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showGuide() {
        AlertDialog.Builder(context).setTitle("远程工作台 · 怎么用")
            .setMessage(
                "【点画面】\n" +
                    "自然触控（默认）：点 = 左键，单指滑 = 滚轮，长按再拖 = 拖动；双指点 = 右键，双指张合 = 缩放，" +
                    "双指横划 = 切换应用，双指长按 = 打开键鼠条的按键。\n" +
                    "精密鼠标：单指移动箭头，点一下 = 左键，双指上下 = 滚轮。\n\n" +
                    "【悬浮球】点一下打开常用按钮（要等一下下，分清是不是双击），那一圈朝屏幕中间开。" +
                    "双击：语音条、键鼠条有展开着的就都收成小把手，都收着就都展开（隐藏了的不动）。" +
                    "按住能拖，松手吸到最近的一条边（上下左右都行）。里面的「语音」「键鼠」：点一下显示，再点一下隐藏。" +
                    "「发图片」：先用手机截屏，再点它选图发到电脑（发一张会顺便放进电脑剪贴板）。\n\n" +
                    "【语音条、键鼠条】点标题收成小把手，小把手点一下展开，按住都能拖。" +
                    "两条都展开、叠在一起时自动错开（横条上下错开，竖条左右错开），只是显示时让开，不改你拖好的位置。" +
                    "键鼠条的 Ctrl / Shift / Alt / Win：点一下带一次（下一次点击或按键用掉），再点锁住，再点松开。\n\n" +
                    "【快捷发送】语音条上点「快捷发送▾」：点一下把存好的话打进电脑光标处，按住能改、往前挪、删除，按住再拖能排顺序；" +
                    "「＋ 新建」再存一条，勾上「打完按回车」会直接发出去。\n\n" +
                    "【侧栏】右边那根小竖条：点一下展开，再点一下收起；点里面任何一项，做完侧栏自己收起。" +
                    "横屏时塞在画面右边的空白里，不挪画面也不盖画面（空白太窄、开着「铺满屏幕」时才盖在画面右边）。" +
                    "「语音设置」能换识别方式、开关边说边打字。" +
                    "「闲时半透明」：开着时语音条、键鼠条和小把手 3 秒没碰就变淡，一碰就恢复（录音、弹出块开着时不变淡）。" +
                    "「竖条」：两条改成竖着的窄条，好塞进横屏两边的空白；「编辑▾」「快捷▾」「按键▾」从条旁边弹出来，点别处收回。\n\n" +
                    "【调整布局】侧栏里点「调整布局」：拖着挪位置、拉蓝点改大小；点按钮能换、能拿掉、能挪到别处；" +
                    "点「＋」加按钮；点「完成」，电脑确认了才保存。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    // ───────── 调整布局 ─────────

    private fun startEditing() {
        if (!layoutSupported) return host.notice("电脑上的蒜鸟要先更新，才能在这里调整布局")
        if (saveGate.busy) return host.notice("上一次的布局还在等电脑确认")
        val e = WorkbenchEditor(this, layout)
        editor = e
        session.setRailOpen(isLandscape, false)
        // 调整布局时竖条的两个弹出块都摆出来；退出时都收回
        popName = null
        popRow = null
        addView(e.topBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(48), Gravity.TOP).apply { setMargins(dp(8), dp(8), dp(8), 0) })
        rebuildContents()
        applyAll(animate = false)
        openRing()
        host.notice("调整布局：拖着挪、拉蓝点改大小，点按钮换或拿掉，点「＋」加按钮")
    }

    internal fun finishEditing(saved: Boolean) {
        val e = editor ?: return
        removeView(e.topBar)
        editor = null
        cancelSaveTimeout()
        if (saved) {
            session.clearPositions()
            host.notice("布局已保存")
        } else saveGate.abandon()?.let { signature ->
            // 点了「完成」还在等电脑确认就不保存了：放开闸门。不放的话请求丢了以后，调整布局、挪快捷发送
            // 一直说「上一次的布局还在等电脑确认」，直到退出全屏（审查 #11）。记下那份布局：电脑其实存上了的话推回来时说一声
            if (signature != layout.signature()) {
                abandonedSave = signature
                abandonedUntil = SystemClock.elapsedRealtime() + 15_000L
            }
        }
        ball.close()
        rebuildContents()
        applyAll(animate = false)
    }

    /** 系统返回（小米从屏幕边上一划也是返回）：调整布局时先问「这次的改动不保存了？」，不直接退出全屏把草稿扔掉（审查 #4）。返回 true = 这里处理了 */
    fun onBack(): Boolean {
        val e = editor ?: return false
        e.cancel()
        return true
    }

    internal fun onDraftChanged() {
        rebuildContents()
        applyAll(animate = false)
        if (!ball.isOpen) openRing()
    }

    internal fun notice(text: String) = host.notice(text)

    internal fun sendToPc(message: JSONObject): Boolean {
        val sent = host.sendToPc(message)
        if (!sent) host.notice("没发出去：请检查电脑连接")
        return sent
    }

    internal fun saveDraft(draft: WorkbenchLayoutV3) =
        saveLayout(draft, "已发给电脑，等它确认", "电脑 10 秒没确认，布局可能没存上，请再点一次「完成」")

    /**
     * 把布局交给电脑校验保存（调整布局的「完成」、快捷发送里挪顺序都走这里）。
     * 电脑推回一模一样的布局才算存上（WorkbenchSaveGate）；10 秒没回就说 timeoutNotice。
     */
    private fun saveLayout(next: WorkbenchLayoutV3, sentNotice: String?, timeoutNotice: String) {
        if (saveGate.busy) return host.notice("上一次的布局还在等电脑确认")
        val id = "wb3-" + SystemClock.elapsedRealtime() + "-" + (++requestSeq)
        if (!saveGate.begin(id, next.signature(), SystemClock.elapsedRealtime())) return
        val sent = sendToPc(JSONObject().put("t", "screenWorkbenchV3Layout").put("workbench", next.toJson()).put("requestId", id))
        if (!sent) {
            saveGate.abort(id)
            return
        }
        cancelSaveTimeout()
        val timeout = Runnable {
            if (saveGate.expire(id, SystemClock.elapsedRealtime())) {
                saveTimeout = null
                host.notice(timeoutNotice)
            }
        }
        saveTimeout = timeout
        postDelayed(timeout, 10_000L)
        sentNotice?.let { host.notice(it) }
    }

    internal fun resetLayout() {
        val id = "wb3-reset-" + SystemClock.elapsedRealtime()
        if (sendToPc(JSONObject().put("t", "screenWorkbenchV3Reset").put("requestId", id))) {
            pendingReset = id
            host.notice("恢复默认中，等电脑确认")
        }
    }

    fun onSaveResult(requestId: String, ok: Boolean, error: String) {
        if (requestId == pendingReset) {
            pendingReset = null
            if (ok) finishEditing(saved = true) else host.notice("电脑没恢复默认：$error")
            return
        }
        if (!saveGate.onAck(requestId, ok)) return
        if (!ok) {
            cancelSaveTimeout()
            host.notice("电脑没保存：$error")
        }
    }

    private fun cancelSaveTimeout() {
        saveTimeout?.let { removeCallbacks(it) }
        saveTimeout = null
    }

    fun onKeyEditorSaved(reply: JSONObject) {
        // 快捷发送里发的（新建、改、删）：自己说结果，就算这时已经进了调整布局也不交给它
        val quick = quickRequests.remove(reply.optString("requestId"))
        val e = editor
        when {
            quick != null -> host.notice(if (reply.optBoolean("ok")) quick else "电脑没保存：" + reply.optString("error"))
            e != null -> e.onKeyEditorSaved(reply)
            !reply.optBoolean("ok") -> host.notice("电脑没保存这个按键：" + reply.optString("error"))
        }
    }

    /** 调整布局时：按住拖动（排顺序 / 换位置），没拖就当点了一下 */
    private fun editDrag(view: View, onTap: () -> Unit, onDrop: (Float, Float) -> Unit) {
        var sx = 0f
        var sy = 0f
        var bx = 0f
        var by = 0f
        var dragging = false
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY
                    bx = v.translationX; by = v.translationY
                    dragging = false
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (!dragging && hypot(dx, dy) > dp(8)) {
                        dragging = true
                        v.alpha = .75f
                    }
                    if (dragging) {
                        v.translationX = bx + dx
                        v.translationY = by + dy
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 1f
                    v.translationX = bx
                    v.translationY = by
                    if (dragging) onDrop(e.rawX, e.rawY) else onTap()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    v.translationX = bx
                    v.translationY = by
                }
            }
            true
        }
    }

    private fun chip() = TextView(context).apply {
        visibility = View.GONE
        gravity = Gravity.CENTER
        textSize = 11.5f
        typeface = Ui.font(context, 800)
        setTextColor(palette.tile)
        setPadding(dp(12), 0, dp(12), 0)
        background = Ui.round(Ui.alpha(palette.fg, .88f), dp(15).toFloat())
        elevation = dp(9).toFloat()
    }

    fun release() {
        releaseModifiers()
        removeCallbacks(barsTask)
        // 停掉闲时半透明的计时
        voice.fadeWhenIdle = false
        keymouse.fadeWhenIdle = false
        cancelSaveTimeout()
        saveGate.reset()
        quickRequests.clear()
        keyboardDialog?.dismiss()
        keyboardDialog = null
        keyboardBox = null
        keyboardDraft = ""
        finishTaskSwitch(false)
        host.cancelSelection()
        interaction.reset()
        mic.onActiveChanged = null
        mic.onRecordingPhaseChanged = null
        mic.stopListening()
        ball.close()
        (mic.parent as? ViewGroup)?.removeView(mic)
    }

    private companion object {
        val NAMES = listOf("voice", "keymouse")
    }
}
