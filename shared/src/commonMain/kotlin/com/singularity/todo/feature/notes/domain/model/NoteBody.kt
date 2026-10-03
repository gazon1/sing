package com.singularity.todo.feature.notes.domain.model

import com.singularity.todo.feature.notes.LinkSchemes
import com.singularity.todo.feature.notes.domain.logic.extractOutgoingLinks
import kotlinx.serialization.Serializable

/**
 * Canonical body representation for a [Note][com.singularity.todo.feature.notes.Note].
 *
 * The canonical format is HTML. All other representations are **derived projections**:
 *
 * | Projection | Computed | Stored |
 * |-----------|----------|--------|
 * | `plainText` | on write | as `wordCount`/`charCount` columns |
 * | `markdown` | on demand (lazy) | never |
 * | `outgoingLinks` | on write | as `outgoing_links` column |
 *
 * **This file is free of any UI/library dependency on purpose.** Every projection here
 * is plain string work, which is what makes [NoteBody] unit-testable in `commonTest`
 * with no fakes and no editor. The single place that needs the richeditor library —
 * markdown ⇄ HTML — lives in [NoteBodyMarkdown.kt] and is enforced by
 * `arch/NoteBodyEncapsulationTest`.
 *
 * **Why `fromMarkdown` is a companion method.** A `NoteBodyCodec` abstraction with a
 * single implementation would conflict with two independent constraints:
 * `singularity-todo-rich-editor` removed `MarkdownHtmlPort` ("the library handles this
 * natively"), and `kotlin-idioms` / Rule of Three forbids abstractions with single
 * implementations. The companion keeps the call site natural — `NoteBody.fromMarkdown(md)`
 * reads as a constructor — while the implementation stays in [NoteBodyMarkdown.kt].
 *
 * @param html The canonical HTML representation as produced by
 *             [NoteBodyMarkdown.markdownToHtml].
 */
@Serializable
@JvmInline
value class NoteBody(val html: String) {

    /** True when the body contains no text content (blank or only HTML tags). */
    val isEmpty: Boolean get() = html.isBlank()

    /**
     * Human-readable plain text, suitable for search indexing and previews.
     * Tags are stripped, HTML entities are decoded.
     *
     * Computed with plain string work rather than by asking the editor to render, so
     * that a `NoteCard` row does not allocate a `RichTextState` per frame.
     */
    val plainText: String
        get() = decodeHtmlEntities(stripTags(html))

    /** Word count derived from [plainText]. */
    val wordCount: Int
        get() {
            val text = decodeHtmlEntities(stripTags(html))
            return text.split(Regex("\\s+")).count { it.isNotBlank() }
        }

    /** Character count (HTML string length). */
    val charCount: Int get() = html.length

    /**
     * Outgoing note/task link URLs extracted from the HTML.
     * These are written to the `outgoing_links` column on every save.
     *
     * The `task://<id>` token is **added** to (not replacing) any existing structural
     * `taskId` linkage — see [ADR 2026-10-02-note-entity-dual-task-linkage].
     * Collection properties use union semantics (not LWW) per the sync skill.
     */
    val outgoingLinks: List<String>
        get() = extractOutgoingLinks(html).map { ref ->
            when (ref) {
                is LinkRef.Note -> "${LinkSchemes.NOTE_PREFIX}${ref.noteId}"
                is LinkRef.Task -> "${LinkSchemes.TASK_PREFIX}${ref.taskId}"
            }
        }

    companion object {
        /** Empty body (no content). */
        val Empty = NoteBody("")

        /**
         * Converts markdown from external sources (AI, backup, template) to the canonical [NoteBody].
         *
         * Called only at the boundaries where external input enters the system:
         * - AI tool input, backup restore, template copy
         * NOT on every keystroke — the canonical HTML path is write-only.
         *
         * The conversion itself lives in [NoteBodyMarkdown.kt] so this file stays free
         * of the editor library.
         */
        fun fromMarkdown(markdown: String): NoteBody = NoteBody(markdownToHtml(markdown))
    }
}

/** A resolved outgoing link extracted from [NoteBody.html]. */
sealed interface LinkRef {
    data class Note(val noteId: String) : LinkRef
    data class Task(val taskId: String) : LinkRef
}

/** Strips HTML tags from a string. */
private fun stripTags(html: String): String = html.replace(Regex("<[^>]*>"), "")

/** Decodes the most common HTML entities found in richeditor output. */
private fun decodeHtmlEntities(html: String): String = buildString {
    var i = 0
    while (i < html.length) {
        if (html[i] == '&') {
            val semicolon = html.indexOf(';', i)
            if (semicolon == -1) {
                append('&')
                i++
            } else {
                val entity = html.substring(i, semicolon + 1)
                append(decodeHtmlEntityToken(entity))
                i = semicolon + 1
            }
        } else {
            append(html[i])
            i++
        }
    }
}

/** Decodes a single HTML entity token. */
private fun decodeHtmlEntityToken(entity: String): String = when (entity) {
    "&amp;" -> "&"
    "&lt;" -> "<"
    "&gt;" -> ">"
    "&quot;" -> "\""
    "&apos;" -> "'"
    "&nbsp;" -> " "
    "&#39;" -> "'"
    "&#x27;" -> "'"
    else -> entity
}

/**
 * Readable snippet for cards and list rows, truncated to [maxChars].
 *
 * Delegates to [plainText] on purpose: this used to be a `String` extension that ran
 * its own `stripTags` and left entities encoded, so a body containing `Tom &amp; Jerry`
 * rendered as "Tom &amp; Jerry" in a card while `plainText` reported "Tom & Jerry".
 * Two definitions of "readable text" is exactly the kind of drift that survives review,
 * so there is now one — and it is a [NoteBody] method so it cannot be pointed at a raw
 * HTML string by mistake.
 */
fun NoteBody.stripPreview(maxChars: Int = 120): String {
    val text = plainText
    return if (text.length <= maxChars) {
        text
    } else {
        text.take(maxChars).removeSuffix(" ") + "…"
    }
}
