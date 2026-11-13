package dev.suanniao

import android.app.AlertDialog
import android.content.Context
import android.os.SystemClock
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * 新建 / 修改 / 删除正式版热键和常用文本（工作台「调整布局」、语音条「快捷发送」里用）。这里只生成 keyEditorSave 请求交给电脑：
 * 按键建在正式版的「远程快捷键」页，电脑校验、保存、推回配置；手机不另存一份按键映射。
 * 双击、长按、多步这些复杂的，到电脑上的「映射」里配。
 */
internal object HotkeyDialogs {
    private const val PAGE = "remote-shortcuts"
    private val MODIFIERS = listOf("Ctrl", "Alt", "Shift", "Win")
    private var seq = 0L

    /** 电脑上标了「按之前先确认」的按键（比如 Alt+F4），单击前问的话：工作台和普通按键页用同一套字 */
    fun confirmTitle(label: String) = "确定发送「$label」？"
    const val CONFIRM_MESSAGE = "会马上发给电脑当前的窗口（比如 Alt+F4 会把它关掉，没保存的内容可能丢）。"

    /** 工作台里用的确认框（普通按键页用自绘的 Overlays.dialog，字一样） */
    fun confirmSend(context: Context, label: String, onSend: () -> Unit) {
        AlertDialog.Builder(context).setTitle(confirmTitle(label))
            .setMessage(CONFIRM_MESSAGE)
            .setPositiveButton("发送") { _, _ -> onSend() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun requestId(prefix: String) = "$prefix-" + SystemClock.elapsedRealtime() + "-" + (++seq)

    private fun supported(context: Context, keyEditor: JSONObject, profileId: String): Boolean {
        if (keyEditor.optInt("version") == 1 && profileId.isNotBlank()) return true
        AlertDialog.Builder(context).setMessage("电脑上的蒜鸟要先更新，才能在手机上建热键")
            .setPositiveButton("知道了", null).show()
        return false
    }

    /** 新建热键：先选「自己输入组合键」还是电脑带的预设 */
    fun createKey(context: Context, keyEditor: JSONObject, profileId: String, onRequest: (JSONObject) -> Unit) {
        if (!supported(context, keyEditor, profileId)) return
        val presets = keyEditor.optJSONArray("presets") ?: JSONArray()
        val grouped = linkedMapOf<String, MutableList<JSONObject>>()
        for (i in 0 until presets.length()) {
            val p = presets.optJSONObject(i) ?: continue
            grouped.getOrPut(p.optString("group").ifBlank { "其他" }) { mutableListOf() }.add(p)
        }
        val groups = grouped.keys.toList()
        val items = listOf("自己输入组合键") + groups.map { "预设 · $it（${grouped[it]?.size ?: 0}）" }
        AlertDialog.Builder(context).setTitle("新建热键")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) keyForm(context, null, null, profileId, onRequest)
                else {
                    val choices = grouped[groups[which - 1]].orEmpty()
                    AlertDialog.Builder(context).setTitle(items[which])
                        .setItems(choices.map { it.optString("label") }.toTypedArray()) { _, i ->
                            keyForm(context, null, choices[i], profileId, onRequest)
                        }
                        .setNegativeButton("取消", null).show()
                }
            }
            .setNegativeButton("取消", null).show()
    }

    fun editKey(context: Context, existing: JSONObject, profileId: String, onRequest: (JSONObject) -> Unit) =
        keyForm(context, existing, null, profileId, onRequest)

    /**
     * 新建常用文本。workbenchRow = "quickText"：电脑建好按键后顺便放进语音条「快捷发送」的最后（同一次写配置），
     * 手机不用再另存一次布局；调整布局里新建的不带它（草稿由手机自己放）。
     */
    fun createText(
        context: Context, keyEditor: JSONObject, profileId: String,
        title: String = "新建常用文本", workbenchRow: String? = null, onRequest: (JSONObject) -> Unit,
    ) {
        if (supported(context, keyEditor, profileId)) textForm(context, null, profileId, title, workbenchRow, onRequest)
    }

    fun editText(context: Context, existing: JSONObject, profileId: String, title: String = "改常用文本", onRequest: (JSONObject) -> Unit) =
        textForm(context, existing, profileId, title, null, onRequest)

    /**
     * 删掉一条常用文本（快捷发送里按住 →「删除」）：先问一句再发。电脑只删「可编辑的常用文本」按键，
     * 从它所在的页删掉，工作台里用到它的地方一起拿掉；名字对不上（别处刚改过）就不删。
     */
    fun deleteText(context: Context, existing: JSONObject, keyEditor: JSONObject, profileId: String, onRequest: (JSONObject) -> Unit) {
        if (!supported(context, keyEditor, profileId)) return
        val label = existing.optString("label")
        AlertDialog.Builder(context).setTitle("删除「$label」？")
            .setMessage("电脑上的这个按键也一起删掉，删了找不回来。")
            .setPositiveButton("删除") { _, _ ->
                onRequest(JSONObject().put("t", "keyEditorSave").put("requestId", requestId("del"))
                    .put("profileId", profileId).put("operation", "deleteText")
                    .put("buttonId", existing.optString("id")).put("expectedLabel", label))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun keyForm(context: Context, existing: JSONObject?, preset: JSONObject?, profileId: String, onRequest: (JSONObject) -> Unit) {
        val d = context.resources.displayMetrics.density
        val currentKeys = existing?.optJSONArray("keys") ?: preset?.optJSONArray("keys")
        val chosen = (0 until (currentKeys?.length() ?: 0)).map { currentKeys!!.optString(it) }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        val name = EditText(context).apply {
            hint = "按钮名字（比如 保存）"
            setSingleLine(true)
            setText(existing?.optString("label") ?: preset?.optString("label") ?: chosen.joinToString("+"))
        }
        panel.addView(name)
        val checks = MODIFIERS.map { modifier ->
            CheckBox(context).apply {
                text = modifier
                isChecked = chosen.any { it.equals(modifier, ignoreCase = true) }
                panel.addView(this)
            }
        }
        val mainKey = EditText(context).apply {
            hint = "主键，比如 S、F4、Tab、1"
            setSingleLine(true)
            setText(chosen.lastOrNull { key -> MODIFIERS.none { it.equals(key, true) } }.orEmpty())
        }
        panel.addView(mainKey)
        panel.addView(TextView(context).apply {
            text = "双击、长按、多步动作到电脑上的「映射」里配"
            textSize = 11f
            setPadding(0, (6 * d).toInt(), 0, (8 * d).toInt())
        })
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (existing == null) "新建热键" else "改热键")
            .setView(panel)
            .setPositiveButton("保存到电脑", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = mainKey.text.toString().trim()
                val label = name.text.toString().trim()
                if (raw.isEmpty() || raw.length > 32 || label.isEmpty()) {
                    Toast.makeText(context, "请填名字和主键", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val keys = JSONArray()
                checks.forEachIndexed { i, check -> if (check.isChecked) keys.put(MODIFIERS[i]) }
                keys.put(raw)
                val message = JSONObject().put("t", "keyEditorSave").put("profileId", profileId).put("keys", keys)
                if (existing == null) {
                    message.put("requestId", requestId("key")).put("operation", "create")
                        .put("pageId", PAGE).put("label", label)
                } else {
                    // 名字里写着旧组合键、又改了组合键：名字跟着换成新的，免得显示错
                    val originalLabel = existing.optString("label")
                    val oldChord = chosen.joinToString("+")
                    val newChord = (0 until keys.length()).joinToString("+") { keys.optString(it) }
                    val saveLabel = if (label == originalLabel && !oldChord.equals(newChord, true) &&
                        label.contains(oldChord, true)) newChord else label
                    message.put("requestId", requestId("key")).put("operation", "edit")
                        .put("buttonId", existing.optString("id"))
                        .put("expectedStep", existing.optJSONObject("step"))
                        .put("expectedLabel", originalLabel)
                        .put("label", saveLabel)
                }
                onRequest(message)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun textForm(
        context: Context, existing: JSONObject?, profileId: String, title: String, workbenchRow: String?,
        onRequest: (JSONObject) -> Unit,
    ) {
        val d = context.resources.displayMetrics.density
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * d).toInt(), (10 * d).toInt(), (18 * d).toInt(), 0)
        }
        fun caption(text: String) = panel.addView(TextView(context).apply {
            this.text = text
            textSize = 11f
            setPadding((2 * d).toInt(), (6 * d).toInt(), 0, 0)
        })
        caption("名字（按钮上显示，短一点）")
        val name = EditText(context).apply {
            hint = "比如 收到"
            setSingleLine(true)
            setText(existing?.optString("label").orEmpty())
        }
        panel.addView(name)
        caption("要打的字（可以多行，最多 2000 字）")
        val content = EditText(context).apply {
            hint = "比如 收到，我马上看一下。"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
            setText(existing?.optString("text").orEmpty())
        }
        panel.addView(content)
        // 默认不按回车，免得误发；聊天里想直接发出去的才勾（电脑在「粘贴」后面加一步回车键）
        val enter = CheckBox(context).apply {
            text = "打完按回车（直接发出去）"
            isChecked = existing?.optBoolean("enter") == true
        }
        panel.addView(enter)
        panel.addView(TextView(context).apply {
            text = "存在电脑上，电脑「映射」里也能改。别存密码。"
            textSize = 11f
            setPadding(0, (4 * d).toInt(), 0, (9 * d).toInt())
        })
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(panel)
            .setPositiveButton("保存到电脑", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = name.text.toString().trim()
                val value = content.text.toString()
                if (label.isBlank() || label.length > 80 || label.any { it.isISOControl() } ||
                    value.isBlank() || value.length > 2000 ||
                    value.any { it.isISOControl() && it != '\n' && it != '\t' }) {
                    Toast.makeText(context, "名字最多 80 字，文字最多 2000 字，不能是空的", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val request = JSONObject().put("t", "keyEditorSave").put("requestId", requestId("text"))
                    .put("profileId", profileId).put("text", value).put("label", label)
                    .put("enter", enter.isChecked)
                if (existing == null) {
                    request.put("operation", "createText").put("pageId", PAGE)
                    if (workbenchRow != null) request.put("workbenchRow", workbenchRow)
                } else request.put("operation", "editText").put("buttonId", existing.optString("id"))
                    .put("expectedStep", existing.optJSONObject("step"))
                    .put("expectedLabel", existing.optString("label"))
                onRequest(request)
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
