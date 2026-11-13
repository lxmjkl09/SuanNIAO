package dev.suanniao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbenchSessionTest {
    private val map = HashMap<String, String>()
    private val session = WorkbenchSession(object : WorkbenchSession.Store {
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    })

    @Test fun defaultsAreOpenInPortraitAndCollapsedInLandscape() {
        val pv = session.tool(false, "voice")
        assertEquals(WorkbenchSession.State.OPEN, pv.state)
        assertFalse(pv.extra)
        assertTrue(session.tool(false, "keymouse").extra)
        val lk = session.tool(true, "keymouse")
        assertEquals(WorkbenchSession.State.COLLAPSED, lk.state)
        assertEquals(0.905f, lk.pillX!!, 1e-6f)
        assertNull(pv.x)
        assertFalse(session.railOpen(true))
    }

    @Test fun orientationsAreIndependentAndPositionsCanBeCleared() {
        session.setTool(true, "voice", WorkbenchSession.Tool(WorkbenchSession.State.HIDDEN, true, 0.2f, 0.3f, 0.1f, 0.9f))
        session.setBall(true, 0.5f, 0.6f)
        session.setRailOpen(true, true)
        assertEquals(WorkbenchSession.State.HIDDEN, session.tool(true, "voice").state)
        assertEquals(WorkbenchSession.State.OPEN, session.tool(false, "voice").state)
        assertEquals(0.5f to 0.6f, session.ball(true))
        assertTrue(session.railOpen(true))
        session.clearPositions()
        val after = session.tool(true, "voice")
        assertEquals(WorkbenchSession.State.HIDDEN, after.state)
        assertNull(after.x)
        assertNull(session.ball(true))
    }

    @Test fun garbageValuesFallBackToDefaults() {
        map["wb.l.voice.state"] = "EXPLODED"
        map["wb.l.voice.x"] = "NaN"
        map["wb.l.voice.y"] = "7"
        val t = session.tool(true, "voice")
        assertEquals(WorkbenchSession.State.COLLAPSED, t.state)
        assertNull(t.x)
        assertNull(t.y)
    }

    @Test fun onlyOneVoiceRowIsOpenAtATime() {
        assertNull(session.voiceRow(false))
        assertEquals(WorkbenchSession.VoiceRow.EDIT, session.toggleVoiceRow(false, WorkbenchSession.VoiceRow.EDIT))
        assertEquals(WorkbenchSession.VoiceRow.EDIT, session.voiceRow(false))
        // 点「快捷发送▾」：换成快捷发送那排，编辑那排跟着收起
        assertEquals(WorkbenchSession.VoiceRow.QUICK, session.toggleVoiceRow(false, WorkbenchSession.VoiceRow.QUICK))
        assertEquals(WorkbenchSession.VoiceRow.QUICK, session.voiceRow(false))
        assertTrue(session.tool(false, "voice").extra)
        // 再点一次：收起
        assertNull(session.toggleVoiceRow(false, WorkbenchSession.VoiceRow.QUICK))
        assertNull(session.voiceRow(false))
        assertFalse(session.tool(false, "voice").extra)
    }

    @Test fun voiceRowIsRememberedPerOrientation() {
        session.toggleVoiceRow(false, WorkbenchSession.VoiceRow.QUICK)
        assertNull(session.voiceRow(true))
        session.toggleVoiceRow(true, WorkbenchSession.VoiceRow.EDIT)
        assertEquals(WorkbenchSession.VoiceRow.QUICK, session.voiceRow(false))
        assertEquals(WorkbenchSession.VoiceRow.EDIT, session.voiceRow(true))
        // 收起、展开语音条本身，拖动位置，都不影响记着的那一排
        val t = session.tool(false, "voice")
        session.setTool(false, "voice", t.copy(state = WorkbenchSession.State.COLLAPSED, x = 0.1f))
        session.setTool(false, "voice", session.tool(false, "voice").copy(state = WorkbenchSession.State.OPEN))
        session.clearPositions()
        assertEquals(WorkbenchSession.VoiceRow.QUICK, session.voiceRow(false))
    }

    @Test fun idleFadeAndVerticalBarsAreOffByDefaultSharedByBothOrientationsAndSurviveLayoutChanges() {
        assertFalse(session.idleFade)
        assertFalse(session.verticalBars)
        session.idleFade = true
        session.verticalBars = true
        assertTrue(session.idleFade)
        assertTrue(session.verticalBars)
        // 电脑上的布局变了只清拖出来的位置，两个侧栏开关留着
        session.clearPositions()
        assertTrue(session.idleFade)
        assertTrue(session.verticalBars)
        session.verticalBars = false
        assertFalse(session.verticalBars)
        assertTrue(session.idleFade)
    }

    @Test fun olderSessionsWithTheEditRowOpenStillShowIt() {
        // 升级前只有「编辑▾」一排：存过 extra=true、没存是哪一排
        map["wb.p.voice.extra"] = "true"
        assertEquals(WorkbenchSession.VoiceRow.EDIT, session.voiceRow(false))
        map["wb.p.voice.row"] = "SOMETHING"
        assertEquals(WorkbenchSession.VoiceRow.EDIT, session.voiceRow(false))
    }
}
