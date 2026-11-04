package com.singularity.todo.feature.notes

import androidx.compose.runtime.Stable

/**
 * Formatting action for the rich-text toolbar.
 *
 * Sealed hierarchy so `when` over [EditorAction] can be exhaustive without
 * falling back to string keys.
 */
@Stable
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
        /**
         * The formatting actions that sit on the bar itself.
         *
         * Three, not seven: the bar also holds Undo, Redo, the overflow trigger and
         * the AI action, and seven 48 dp targets are 336 dp — the width of a 360 dp
         * phone with nothing to spare. Everything else lives in [overflow], and the
         * bar scrolls, so a narrower window still reaches all of it.
         */
        val primary: List<EditorAction> =
            listOf(
                Bold,
                Italic,
                Underline,
            )

        /**
         * The actions behind the overflow menu.
         *
         * Headings first, so H1/H2/H3 sit together under their own glyphs.
         *
         * [Quote] is not offered: the rich-text library has no blockquote toggle, so
         * a menu item for it would do nothing.
         */
        val overflow: List<EditorAction> =
            listOf(
                H1,
                H2,
                H3,
                Strike,
                Code,
                Bullet,
                Ordered,
                AlignLeft,
                AlignCenter,
                AlignRight,
                ExternalLink,
                InternalLink,
            )
    }
}
