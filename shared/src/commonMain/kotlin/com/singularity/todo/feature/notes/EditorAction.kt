package com.singularity.todo.feature.notes

/**
 * Formatting action for the rich-text toolbar.
 *
 * Sealed hierarchy so `when` over [EditorAction] can be exhaustive without
 * falling back to string keys.
 */
sealed interface EditorAction {
    // ─── Inline spans ──────────────────────────────────────────────────────
    data object Bold : EditorAction
    data object Italic : EditorAction
    data object Underline : EditorAction
    data object Strike : EditorAction
    data object Code : EditorAction

    // ─── Headings ──────────────────────────────────────────────────────────
    data object H1 : EditorAction
    data object H2 : EditorAction
    data object H3 : EditorAction

    // ─── Block elements ─────────────────────────────────────────────────────
    data object Bullet : EditorAction
    data object Ordered : EditorAction
    data object Quote : EditorAction // blockquote via toggleBlockquote()

    // ─── Alignment ────────────────────────────────────────────────────────
    data object AlignLeft : EditorAction
    data object AlignCenter : EditorAction
    data object AlignRight : EditorAction

    // ─── Links ────────────────────────────────────────────────────────────

    /** Opens a URL input dialog; inserts an external http(s) link. */
    data object ExternalLink : EditorAction

    /** Opens the internal link picker (Obsidian-style [[Note]] / [[Task]] linking). */
    data object InternalLink : EditorAction

    companion object {
        /** Toolbar primary row — common formatting actions. */
        val primary: List<EditorAction> = listOf(
            Bold,
            Italic,
            Underline,
            Strike,
            H1,
            Bullet,
            Ordered,
        )

        /** Overflow menu actions — less common or dialog-triggered. */
        val overflow: List<EditorAction> = listOf(
            H2, H3, Code, Quote,
            AlignLeft, AlignCenter, AlignRight,
            ExternalLink, InternalLink,
        )
    }
}
