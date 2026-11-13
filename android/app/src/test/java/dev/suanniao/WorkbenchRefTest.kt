package dev.suanniao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbenchRefTest {
    @Test fun parsesEachKindAndRoundTrips() {
        assertEquals(WorkbenchRef.Fn("voice"), WorkbenchRef.parse("fn:voice"))
        assertEquals(WorkbenchRef.Key("enter"), WorkbenchRef.parse("key:enter"))
        assertEquals(WorkbenchRef.Button("button-4"), WorkbenchRef.parse("button:button-4"))
        for (t in listOf("fn:voice", "key:enter", "button:button-4", "fn:captureQq", "fn:sendFile")) {
            assertEquals(t, WorkbenchRef.parse(t)!!.token)
        }
    }

    @Test fun rejectsUnknownOrMalformed() {
        for (t in listOf(null, "", "fn:", ":x", "fn:launchRocket", "key:f13", "script:rm", "button", "fn")) {
            assertNull(t, WorkbenchRef.parse(t))
        }
    }

    @Test fun catalogMatchesPcRules() {
        assertEquals(listOf("voice", "keymouse", "pointer"), WorkbenchCatalog.FIXED_BALL)
        assertEquals(listOf("taskSwitch", "captureWechat", "captureQq", "captureSystem", "sendFile"), WorkbenchCatalog.PLACEABLE)
        assertEquals("发图片", WorkbenchCatalog.function("sendFile")?.label)
        assertTrue(WorkbenchCatalog.placeableFunctions().any { it.id == "sendFile" })
        assertEquals(11, WorkbenchCatalog.KEYS.size)
        assertEquals("a" to listOf("alt"), WorkbenchCatalog.CAPTURE_KEYS["captureWechat"])
    }
}
