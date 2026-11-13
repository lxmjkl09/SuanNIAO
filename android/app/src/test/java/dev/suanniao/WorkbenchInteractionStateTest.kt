package dev.suanniao

import org.junit.Assert.*
import org.junit.Test

class WorkbenchInteractionStateTest {
    @Test fun precisionIsAReversibleSingleTapToggle() {
        val state = WorkbenchInteractionState()
        assertEquals(ScreenView.InputMode.PRECISE, state.togglePrecision())
        assertTrue(state.setPointerMode(state.togglePrecision()))
        assertEquals(ScreenView.InputMode.DIRECT, state.togglePrecision())
        assertTrue(state.setPointerMode(state.togglePrecision()))
        assertEquals(ScreenView.InputMode.DIRECT, state.pointerMode)
        assertFalse(state.setPointerMode(ScreenView.InputMode.DIRECT))
    }

    @Test fun viewOnlyIsReversibleWithoutTouchingOtherTasks() {
        val state = WorkbenchInteractionState()
        assertEquals(ScreenView.InputMode.VIEW, state.toggleViewOnly())
        assertTrue(state.setPointerMode(state.toggleViewOnly()))
        assertEquals(ScreenView.InputMode.VIEW, state.pointerMode)
        assertEquals(ScreenView.InputMode.DIRECT, state.toggleViewOnly())
        assertTrue(state.setPointerMode(state.toggleViewOnly()))
        assertEquals(ScreenView.InputMode.DIRECT, state.pointerMode)
        assertTrue(state.setPointerMode(ScreenView.InputMode.PRECISE))
        assertEquals(ScreenView.InputMode.VIEW, state.toggleViewOnly())
        assertTrue(state.setPointerMode(state.toggleViewOnly()))
        assertEquals(ScreenView.InputMode.DIRECT, state.toggleViewOnly())
    }

    @Test fun screenshotAndWindowSwitchAreMutuallyExclusive() {
        val state = WorkbenchInteractionState()
        assertTrue(state.start(WorkbenchInteractionState.Task.SCREEN_SELECTION))
        assertFalse(state.start(WorkbenchInteractionState.Task.WINDOW_SWITCH))
        assertFalse(state.finish(WorkbenchInteractionState.Task.WINDOW_SWITCH))
        assertTrue(state.finish(WorkbenchInteractionState.Task.SCREEN_SELECTION))
        assertTrue(state.start(WorkbenchInteractionState.Task.WINDOW_SWITCH))
        assertTrue(state.finish(WorkbenchInteractionState.Task.WINDOW_SWITCH))
    }

    @Test fun panelsDoNotChangeTheComputerTask() {
        val state = WorkbenchInteractionState()
        state.show(WorkbenchInteractionState.Panel.VOICE_PANEL)
        assertEquals(WorkbenchInteractionState.Task.IDLE, state.task)
        assertTrue(state.start(WorkbenchInteractionState.Task.VOICE_RECORDING))
        state.show(WorkbenchInteractionState.Panel.EDIT_TOOLBAR)
        assertEquals(WorkbenchInteractionState.Task.VOICE_RECORDING, state.task)
        state.reset()
        assertEquals(WorkbenchInteractionState.Task.IDLE, state.task)
        assertEquals(WorkbenchInteractionState.Panel.NONE, state.panel)
    }
}