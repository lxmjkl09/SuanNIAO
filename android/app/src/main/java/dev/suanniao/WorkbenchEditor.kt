package dev.suanniao

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.suanniao.WorkbenchLayoutV3.Container
import dev.suanniao.WorkbenchLayoutV3.Slot
import org.json.JSONObject

/**
 * 调整布局：在工作台画面上直接改。草稿只在手机上，点「完成」交给电脑校验保存，电脑确认并推回来才退出。
 * - 顶栏：取消 · 提示 · 恢复默认 · 完成（跟正式版「调整布局」一样）；
 * - 点按钮：往前挪、挪到最前、移到…、换成别的、改热键 / 改常用文本（正式版按键）、拿掉；固定的只能拖着换位置；
 * - 点「＋」或空槽位：按钮库（动作 / 热键 / 新建 / 基础键），已经放在别处的标出来，选了就是搬过来；
 *   语音条「快捷发送」那排只列热键和新建（那里只能放正式版按键）；
 * - 拖标题挪位置、拉蓝点改大小、拖悬浮球：只改当前方向。
 */
internal class WorkbenchEditor(private val controls: ScreenFocusControls, original: WorkbenchLayoutV3) {
    var draft: WorkbenchLayoutV3 = original
        private set
    /** 电脑上最近一次推来的布局（进来时就是 original）：之后再推来时，比它多出来的要补进草稿 */
    private var base: WorkbenchLayoutV3 = original

    private val context = controls.context
    private val d = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val palette = Ui.palette(context)
    private var changed = false
    /** 新建热键 / 常用文本：请求号 → 放到哪；电脑回了按键 id 以后变成 createdId */
    private var pendingCreate: Pair<String, Slot>? = null
    private var createdId: Pair<String, Slot>? = null

    private val title = TextView(context).apply {
        textSize = 11.5f
        typeface = Ui.font(context, 800)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 2
    }

    val topBar: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), 0, dp(6), 0)
        background = Ui.round(0xFF2F6FED.toInt(), dp(14).toFloat())
        elevation = dp(12).toFloat()
        addView(barButton("取消", false) { cancel() }, LinearLayout.LayoutParams(-2, dp(34)))
        addView(title, LinearLayout.LayoutParams(0, -1, 1f))
        addView(barButton("恢复默认", false) { confirmReset() }, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) })
        addView(barButton("完成", true) { done() }, LinearLayout.LayoutParams(-2, dp(34)))
    }

    init {
        onRotated()
        // 按钮之间的空隙点下去不漏到电脑画面上
        WorkbenchTiles.holdTouches(topBar)
    }

    private fun barButton(label: String, filled: Boolean, action: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 12.5f
        typeface = Ui.font(context, 850)
        gravity = Gravity.CENTER
        setTextColor(if (filled) 0xFF2F6FED.toInt() else Color.WHITE)
        background = Ui.round(if (filled) Color.WHITE else 0x33FFFFFF, dp(10).toFloat())
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { action() }
    }

    fun onRotated() {
        title.text = "调整布局（" + (if (controls.isLandscape) "横屏" else "竖屏") + "）\n位置大小只改这个方向"
    }

    private fun update(next: WorkbenchLayoutV3, refusal: String? = null) {
        if (next === draft) {
            refusal?.let { controls.notice(it) }
            return
        }
        draft = next
        changed = true
        controls.onDraftChanged()
    }

    fun remove(slot: Slot) = update(draft.remove(slot), "固定的按钮拿不掉，只能拖着换位置")
    fun moveWithin(container: Container, from: Int, to: Int) = update(draft.moveWithin(container, from, to))
    fun swapBall(a: Int, b: Int) = update(draft.swapBall(a, b))
    fun moveBall(landscape: Boolean, x: Double, y: Double) = update(draft.withBall(landscape, x, y))

    fun moveTool(landscape: Boolean, name: String, x: Double, y: Double) {
        val t = tool(landscape, name)
        update(draft.withTool(landscape, name, t.copy(x = x.coerceIn(0.0, 1.0), y = y.coerceIn(0.0, 1.0))))
    }

    fun scaleTool(landscape: Boolean, name: String, k: Double) {
        val t = tool(landscape, name)
        update(draft.withTool(landscape, name, t.copy(k = k.coerceIn(0.6, 1.8))))
    }

    private fun tool(landscape: Boolean, name: String) =
        draft.geometry(landscape).let { if (name == "voice") it.voice else it.keymouse }

    fun showOptions(slot: Slot) {
        val ref = draft.items(slot.container).getOrNull(slot.index) ?: return
        val label = controls.describeRef(ref).first
        if (draft.isFixed(slot)) {
            controls.notice("「$label」固定在悬浮球里：按住拖到别的位置能换位置，拿不掉")
            return
        }
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (slot.container != Container.BALL && slot.index > 0) {
            items += "往前挪" to { moveWithin(slot.container, slot.index, slot.index - 1) }
            items += "挪到最前" to { moveWithin(slot.container, slot.index, 0) }
        }
        items += "移到…" to { chooseDestination(ref, slot) }
        items += "换成别的" to { showLibrary(slot, replacing = true) }
        if (ref is WorkbenchRef.Button) {
            controls.keyEditorEntry("editable", ref.id)?.let { e ->
                items += "改热键 / 改名字" to { HotkeyDialogs.editKey(context, e, controls.profileId) { send(it, null) } }
            }
            controls.keyEditorEntry("editableText", ref.id)?.let { e ->
                items += "改常用文本" to { HotkeyDialogs.editText(context, e, controls.profileId) { send(it, null) } }
            }
        }
        items += "拿掉" to { remove(slot) }
        AlertDialog.Builder(context).setTitle(label)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun chooseDestination(ref: WorkbenchRef, from: Slot) {
        // 快捷发送只放正式版按键：动作、基础键不列这一项
        val targets = Container.values().filter { it != from.container && (it != Container.QUICK || ref is WorkbenchRef.Button) }
        AlertDialog.Builder(context).setTitle("移到哪里")
            .setItems(targets.map { it.title }.toTypedArray()) { _, which ->
                val c = targets[which]
                val target = if (c == Container.BALL) {
                    val empty = draft.ball.indexOfFirst { it == null }
                    if (empty < 0) {
                        controls.notice("悬浮球 8 个位置满了：先拿掉一个")
                        return@setItems
                    }
                    Slot(c, empty)
                } else Slot(c, draft.items(c).size)
                update(draft.place(ref, target, from), "放不下：那里已经有这个按钮了，或者一排满了")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showLibrary(target: Slot, replacing: Boolean = false) {
        val dialog = Dialog(context)
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }
        fun header(text: String) = list.addView(TextView(context).apply {
            this.text = text
            textSize = 12f
            typeface = Ui.font(context, 900)
            setTextColor(palette.sub)
            setPadding(dp(4), dp(12), 0, dp(6))
        })
        fun row(text: String, action: () -> Unit) = list.addView(TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(palette.fg)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = Ui.round(palette.tile, dp(10).toFloat(), Ui.hairline(context).toInt(), palette.line)
            setOnClickListener {
                dialog.dismiss()
                action()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        fun item(ref: WorkbenchRef) {
            val (label, glyph) = controls.describeRef(ref)
            val where = if (ref is WorkbenchRef.Key) "" else draft.find(ref)?.let { "　· 在${it.container.title}" } ?: ""
            row(listOfNotNull(glyph, label).joinToString("  ") + where) { pick(ref, target, replacing) }
        }
        // 快捷发送点一下就是按键单击：只列正式版按键（常用文本、热键），动作和基础键放不进去
        val quick = target.container == Container.QUICK
        if (!quick) {
            header("动作（手机这边做的事）")
            WorkbenchCatalog.placeableFunctions().forEach { item(WorkbenchRef.Fn(it.id)) }
        }
        header(if (quick) "快捷发送只放电脑上的按键（常用文本、热键）" else "热键（正式版按键，电脑上「映射」里能改）")
        controls.officialButtons.values.forEach { item(WorkbenchRef.Button(it.id)) }
        row("＋ 新建热键") { HotkeyDialogs.createKey(context, controls.keyEditor, controls.profileId) { send(it, target) } }
        row("＋ 新建常用文本") { HotkeyDialogs.createText(context, controls.keyEditor, controls.profileId) { send(it, target) } }
        if (!quick) {
            header("基础键（语音条和键鼠条可以各放一个）")
            WorkbenchCatalog.KEYS.forEach { item(WorkbenchRef.Key(it.name)) }
        }
        dialog.setContentView(ScrollView(context).apply {
            addView(list)
            background = Ui.round(palette.bg, dp(18).toFloat())
        })
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        val metrics = context.resources.displayMetrics
        dialog.window?.setLayout((metrics.widthPixels * 0.92f).toInt().coerceAtMost(dp(480)), (metrics.heightPixels * 0.8f).toInt())
    }

    private fun pick(ref: WorkbenchRef, target: Slot, replacing: Boolean) {
        var base = draft
        if (replacing) {
            if (base.items(target.container).getOrNull(target.index) == ref) return
            // 换成别的：一排里先拿掉原来那个，新的放在同一格；悬浮球里新的直接顶掉原来那个
            if (target.container != Container.BALL) base = base.remove(target)
        }
        val next = base.place(ref, target, if (ref is WorkbenchRef.Key) null else base.find(ref))
        if (next === base) controls.notice("放不下：那里已经有这个按钮了，或者一排满了") else update(next)
    }

    private fun send(request: JSONObject, target: Slot?) {
        if (!controls.sendToPc(request)) return
        if (target != null) pendingCreate = request.optString("requestId") to target
        controls.notice("已发给电脑，等它保存")
    }

    fun onKeyEditorSaved(reply: JSONObject) {
        val pending = pendingCreate
        val mine = pending != null && pending.first == reply.optString("requestId")
        if (!reply.optBoolean("ok")) {
            if (mine) pendingCreate = null
            controls.notice("电脑没保存：" + reply.optString("error"))
            return
        }
        if (mine) {
            pendingCreate = null
            createdId = reply.optString("buttonId") to pending!!.second
            placeCreated() // 电脑先推配置、再回执：按键一般已经到了
        } else controls.notice("电脑已保存")
    }

    /**
     * 电脑推来新配置（ScreenFocusControls 随后按需要重画）：
     * - 布局变了（比如刚建好一条快捷发送）：草稿没改过就跟着换成新的；改过就把新多出来的补进去（WorkbenchLayoutV3.withAdditions）；
     * - 电脑上删掉的按键：草稿里也拿掉。不然点「完成」被电脑拒绝，按键 id 以后被别的按键用上还会悄悄指过去（审查 #17）；
     * - 新建的按键到了就放进目标位置。
     * theirs：电脑推来的布局，读不出（不合格）是 null，这时只做后两件。
     */
    fun onConfig(theirs: WorkbenchLayoutV3?) {
        if (theirs != null && theirs.signature() != base.signature()) {
            draft = if (changed) draft.withAdditions(base, theirs) else theirs
            base = theirs
        }
        val missing = draft.missingButtons(controls.officialButtons.keys)
        if (missing > 0) {
            draft = draft.withoutMissing(controls.officialButtons.keys)
            controls.notice("电脑上删掉了 $missing 个按键，这里也拿掉了")
        }
        placeCreated()
    }

    /** 新建的按键到了（电脑配置里有了）：放进点「＋」时选的那个位置 */
    private fun placeCreated() {
        val created = createdId ?: return
        if (created.first !in controls.officialButtons) return
        createdId = null
        val next = draft.place(WorkbenchRef.Button(created.first), created.second)
        if (next === draft) controls.notice("新按键建好了，但那个位置放不下；可以在按钮库里再选") else update(next)
    }

    /** 顶栏「取消」，也是系统返回（ScreenFocusControls.onBack）：改过就先问一句 */
    fun cancel() {
        if (!changed) return controls.finishEditing(saved = false)
        AlertDialog.Builder(context).setTitle("这次的改动不保存了？")
            .setPositiveButton("不保存") { _, _ -> controls.finishEditing(saved = false) }
            .setNegativeButton("继续调整", null)
            .show()
    }

    private fun done() {
        if (!changed) return controls.finishEditing(saved = false)
        controls.saveDraft(draft)
    }

    private fun confirmReset() {
        AlertDialog.Builder(context).setTitle("恢复默认布局？")
            .setMessage("悬浮球、语音条、键鼠条、侧栏都回到默认的样子；你建过的热键还留在电脑上。")
            .setPositiveButton("恢复默认") { _, _ -> controls.resetLayout() }
            .setNegativeButton("取消", null)
            .show()
    }
}
