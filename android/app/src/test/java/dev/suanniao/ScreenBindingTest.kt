package dev.suanniao

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** 新视频端口字段不能破坏旧版已经保存的配对信息。 */
class ScreenBindingTest {
    @Test
    fun oldBindingDefaultsToNoScreenPort() {
        val old = JSONObject()
            .put("pc", "fake-pc")
            .put("name", "电脑")
            .put("addrs", org.json.JSONArray().put("192.0.2.20"))
            .put("port", 47820)
            .put("fp", "fake-fingerprint")
            .put("phoneId", "fake-phone")
            .put("token", "fake-sealed-token")
        val restored = LanBinding.fromJson(old)
        assertNotNull(restored)
        assertEquals(0, restored!!.screenPort)
    }

    @Test
    fun roundTripsNewScreenPortWithoutAffectingControlPort() {
        val original = LanBinding(
            pc = "fake-pc", name = "电脑", addrs = listOf("192.0.2.20"),
            port = 47820, fp = "fake-fingerprint", phoneId = "fake-phone",
            sealedToken = "fake-sealed-token", screenPort = 47821
        )
        val restored = LanBinding.fromJson(original.toJson())
        assertEquals(original, restored)
        assertEquals(47820, restored!!.port)
        assertEquals(47821, restored.screenPort)
    }

    @Test
    fun invalidScreenPortIsIgnored() {
        val original = LanBinding(
            pc = "fake-pc", name = "电脑", addrs = listOf("192.0.2.20"),
            port = 47820, fp = "fake-fingerprint", phoneId = "fake-phone",
            sealedToken = "fake-sealed-token"
        )
        val saved = original.toJson().put("screenPort", 70000)
        assertEquals(0, LanBinding.fromJson(saved)!!.screenPort)
    }
}
