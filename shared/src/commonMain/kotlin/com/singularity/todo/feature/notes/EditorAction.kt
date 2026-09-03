package com.singularity.todo.feature.notes

/**
 * Formatting action for the rich-text toolbar.
 * Each action maps to a formatting option in [com.mohamedrejeb.richeditor.ui.material3.RichTextEditor].
 *
 * @JvmInline ensures zero overhead — the underlying String is directly used at runtime.
 */
@JvmInline
value class EditorAction private constructor(private val tag: String) {
    companion object {
        val Bold = EditorAction("bold")
        val Italic = EditorAction("italic")
        val Underline = EditorAction("underline")
        val Strike = EditorAction("strike")
        val H1 = EditorAction("h1")
        val H2 = EditorAction("h2")
        val H3 = EditorAction("h3")
        val Bullet = EditorAction("bullet")
        val Ordered = EditorAction("ordered")
        val Quote = EditorAction("quote")
        val Code = EditorAction("code")
        val Link = EditorAction("link")

        val all: List<EditorAction> = listOf(
            Bold, Italic, Underline, Strike,
            H1, H2, H3,
            Bullet, Ordered, Quote, Code, Link
        )
    }

    val key: String get() = tag
}
