package dev.suanniao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteTypedTextMessageTest {
    @Test fun chineseTextUsesExistingPcProtocolWithoutEnter() {
        val msg = RemoteTypedTextMessage.payload("你好，拼音九键")!!
        assertEquals("text", msg.getString("t"))
        assertEquals("你好，拼音九键", msg.getString("s"))
        assertFalse(msg.getBoolean("enter"))
    }

    @Test fun preserveWhitespaceAndEmojiInText() {
        val msg = RemoteTypedTextMessage.payload("  你好😊\n  ")!!
        assertEquals("  你好😊\n  ", msg.getString("s"))
    }

    @Test fun emptyTextIsNotSent() {
        assertNull(RemoteTypedTextMessage.payload(""))
    }
}
