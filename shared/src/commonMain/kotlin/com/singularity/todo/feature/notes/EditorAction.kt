package com.singularity.todo.feature.notes

/**
 * Formatting action for the rich-text toolbar.
 *
 * Sealed hierarchy so `when` over [EditorAction] can be exhaustive without
 * falling back to string keys. The previous `value class` + private constructor
 * design forced the toolbar code to `when (action.key)` and lose IDE support.
 */
sealed interface EditorAction {
    data object Bold : EditorAction
    data object Italic : EditorAction
    data object Underline : EditorAction
    data object Strike : EditorAction
    data object H1 : EditorAction
    data object H2 : EditorAction
    data object H3 : EditorAction
    data object Bullet : EditorAction
    data object Ordered : EditorAction
    data object Quote : EditorAction
    data object Code : EditorAction
    data object Link : EditorAction

    companion object {
        /** Toolbar ordering — used by [EditorToolbar]. */
        val all: List<EditorAction> = listOf(
            Bold, Italic, Underline, Strike,
            H1, H2, H3,
            Bullet, Ordered, Quote, Code, Link,
        )
    }
}
