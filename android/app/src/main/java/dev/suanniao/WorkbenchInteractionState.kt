package dev.suanniao

/**
 * Single owner of remote-workbench transient tasks.
 *
 * Pointer tracking is independent of these tasks: starting a screenshot never
 * silently exits the user's chosen DIRECT/PRECISE mode. A modal computer
 * task must be finished/cancelled before another is started.
 */
internal class WorkbenchInteractionState {
    enum class Task { IDLE, SCREEN_SELECTION, WINDOW_SWITCH, VOICE_RECORDING }
    enum class Panel { NONE, QUICK_RAIL, EDIT_TOOLBAR, VOICE_PANEL }

    var pointerMode: ScreenView.InputMode = ScreenView.InputMode.DIRECT
        private set

    fun setPointerMode(next: ScreenView.InputMode): Boolean {
        if (pointerMode == next) return false
        pointerMode = next
        return true
    }

    fun togglePrecision(): ScreenView.InputMode =
        if (pointerMode == ScreenView.InputMode.PRECISE)
            ScreenView.InputMode.DIRECT else ScreenView.InputMode.PRECISE

    /** Same control enters and exits view-only. Never strand touch input. */
    fun toggleViewOnly(): ScreenView.InputMode =
        if (pointerMode == ScreenView.InputMode.VIEW)
            ScreenView.InputMode.DIRECT else ScreenView.InputMode.VIEW

    var task: Task = Task.IDLE
        private set
    var panel: Panel = Panel.NONE
        private set

    fun start(target: Task): Boolean {
        require(target != Task.IDLE)
        if (task != Task.IDLE && task != target) return false
        task = target
        return true
    }

    fun finish(expected: Task): Boolean {
        if (task != expected) return false
        task = Task.IDLE
        return true
    }

    fun show(panel: Panel) {
        this.panel = panel
    }

    fun reset() {
        task = Task.IDLE
        panel = Panel.NONE
    }

    fun isBlockingNewTask(): Boolean = task != Task.IDLE
}
