package dev.suanniao

import dev.suanniao.LinkService.LanPhase
import org.junit.Assert.assertEquals
import org.junit.Test

/** 「电脑屏幕」模块没画面时看状态说话（v3.3 U4） */
class ScreenLinkHintTest {
    private fun hint(bt: Boolean, phase: LanPhase, pcOffline: Boolean = false, bound: Boolean = true, revoked: Boolean = false) =
        ScreenLinkHint.text(bt, phase, pcOffline, bound, revoked)

    @Test fun computerWithoutNetworkSaysSoWhileBluetoothStillWorks() {
        assertEquals("电脑的网络断了，正在等它恢复（蓝牙还能控制）", hint(bt = true, phase = LanPhase.WAITING, pcOffline = true))
        // 正在重试也一样：电脑没网，重试多少次都连不上
        assertEquals("电脑的网络断了，正在等它恢复（蓝牙还能控制）", hint(bt = true, phase = LanPhase.CONNECTING, pcOffline = true))
    }

    @Test fun reconnectingAndBluetoothOnlyAreTold() {
        assertEquals("正在重连局域网…", hint(bt = true, phase = LanPhase.CONNECTING))
        assertEquals("正在重连局域网…", hint(bt = false, phase = LanPhase.CONNECTING))
        assertEquals("局域网连不上，蓝牙还能控制", hint(bt = true, phase = LanPhase.WAITING))
        assertEquals("等待局域网连接", hint(bt = false, phase = LanPhase.WAITING))
    }

    @Test fun notBoundOrRevokedAsksToScanAgain() {
        assertEquals("请扫码绑定局域网", hint(bt = true, phase = LanPhase.NONE, bound = false))
        assertEquals("电脑上解除了绑定：重新扫码才能看画面", hint(bt = true, phase = LanPhase.REVOKED))
        assertEquals("电脑上解除了绑定：重新扫码才能看画面", hint(bt = false, phase = LanPhase.WAITING, revoked = true))
    }
}
