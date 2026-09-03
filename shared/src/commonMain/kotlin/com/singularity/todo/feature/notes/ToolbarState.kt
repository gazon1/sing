package com.singularity.todo.feature.notes

/**
 * Represents which formatting actions are currently active in the toolbar.
 * Built via the [toolbarState] DSL.
 */
data class ToolbarState(
    val enabled: Set<EditorAction> = emptySet(),
    val active: Set<EditorAction> = emptySet()
) {
    @DslMarker
    annotation class BuilderDsl

    class Builder {
        private val _enabled = mutableSetOf<EditorAction>()
        private val _active = mutableSetOf<EditorAction>()

        fun enable(actions: Set<EditorAction>) { _enabled += actions }
        fun disable(actions: Set<EditorAction>) { _enabled -= actions }
        fun toggle(action: EditorAction) {
            if (action in _active) _active -= action else _active += action
        }
        fun active(actions: Set<EditorAction>) { _active += actions }

        fun build(): ToolbarState = ToolbarState(_enabled.toSet(), _active.toSet())
    }
}

fun toolbarState(block: ToolbarState.Builder.() -> Unit): ToolbarState =
    ToolbarState.Builder().apply(block).build()
