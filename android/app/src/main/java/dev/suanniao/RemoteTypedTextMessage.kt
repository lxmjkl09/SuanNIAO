package dev.suanniao

import org.json.JSONObject

/**
 * Phone IME composition stays local until the user presses Send.
 *
 * Remote text is inserted at the Windows caret WITHOUT an automatic Enter.
 * The existing PC text pipeline decides whether Unicode is pasted or typed.
 */
internal object RemoteTypedTextMessage {
    fun payload(text: String): JSONObject? {
        if (text.isEmpty()) return null
        return JSONObject()
            .put("t", "text")
            .put("s", text)
            .put("enter", false)
    }
}
