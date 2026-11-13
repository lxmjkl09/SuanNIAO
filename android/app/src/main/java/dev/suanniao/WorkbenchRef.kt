package dev.suanniao

/** 工作台槽位里的一个引用：手机动作、基础键或正式版按键（按 id；按键还在不在由调用方核对）。 */
internal sealed class WorkbenchRef {
    abstract val token: String

    data class Fn(val id: String) : WorkbenchRef() {
        override val token: String get() = "fn:$id"
    }

    data class Key(val name: String) : WorkbenchRef() {
        override val token: String get() = "key:$name"
    }

    data class Button(val id: String) : WorkbenchRef() {
        override val token: String get() = "button:$id"
    }

    companion object {
        fun parse(token: String?): WorkbenchRef? {
            if (token == null || token.length > 120) return null
            val colon = token.indexOf(':')
            if (colon <= 0 || colon == token.lastIndex) return null
            val value = token.substring(colon + 1)
            return when (token.substring(0, colon)) {
                "fn" -> if (WorkbenchCatalog.isFunction(value)) Fn(value) else null
                "key" -> if (WorkbenchCatalog.isKey(value)) Key(value) else null
                "button" -> if (value.length <= 100) Button(value) else null
                else -> null
            }
        }
    }
}
