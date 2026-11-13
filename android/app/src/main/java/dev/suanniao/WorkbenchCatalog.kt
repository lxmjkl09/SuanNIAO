package dev.suanniao

/**
 * 远程工作台里能放的东西（规则和 pc/config/src/workbench.rs 一样，改一边要改另一边）：
 * - 动作（fn:）：手机这边做的事。语音、键鼠、精密鼠标固定在悬浮球里；其余可以放进任何槽位，但只能放一处。
 * - 基础键（key:）：单个按键；同一排不重复，语音条和键鼠条可以各放一个。
 * 侧栏的设置项是固定的，不进槽位（见 WorkbenchRail）。
 */
internal object WorkbenchCatalog {
    data class Function(val id: String, val label: String, val glyph: String)
    /** name 同时是发给电脑的 key 消息里的键名 */
    data class BasicKey(val name: String, val label: String, val glyph: String)

    val FIXED_BALL = listOf("voice", "keymouse", "pointer")
    val PLACEABLE = listOf("taskSwitch", "captureWechat", "captureQq", "captureSystem", "sendFile")

    private val FUNCTIONS = listOf(
        Function("voice", "语音", "◉"),
        Function("keymouse", "键鼠", "⌨"),
        Function("pointer", "精密鼠标", "⌖"),
        Function("taskSwitch", "切换应用", "▦"),
        Function("captureWechat", "微信截图", "▣"),
        Function("captureQq", "QQ截图", "▣"),
        Function("captureSystem", "系统截图", "⬚"),
        // 跟正式版「⋯ → 发文件到电脑」一样：系统选文件（能多选），发到电脑；单张图片电脑顺便放进剪贴板
        Function("sendFile", "发图片", "↥"),
    )

    val KEYS = listOf(
        BasicKey("backspace", "退格", "⌫"), BasicKey("delete", "删除", "Del"),
        BasicKey("space", "空格", "␣"), BasicKey("enter", "回车", "⏎"),
        BasicKey("esc", "Esc", "Esc"), BasicKey("tab", "Tab", "⇥"),
        BasicKey("left", "左", "←"), BasicKey("right", "右", "→"),
        BasicKey("up", "上", "↑"), BasicKey("down", "下", "↓"),
        BasicKey("capslock", "大小写", "Aa"),
    )

    /** 截图动作发的热键：主键 to 修饰键（跟 key 消息一样的名字） */
    val CAPTURE_KEYS = mapOf(
        "captureWechat" to ("a" to listOf("alt")),
        "captureQq" to ("a" to listOf("ctrl", "alt")),
        "captureSystem" to ("s" to listOf("win", "shift")),
    )

    fun function(id: String): Function? = FUNCTIONS.firstOrNull { it.id == id }
    fun key(name: String): BasicKey? = KEYS.firstOrNull { it.name == name }
    fun isFunction(id: String): Boolean = FUNCTIONS.any { it.id == id }
    fun isKey(name: String): Boolean = KEYS.any { it.name == name }
    fun placeableFunctions(): List<Function> = FUNCTIONS.filter { it.id in PLACEABLE }
}
