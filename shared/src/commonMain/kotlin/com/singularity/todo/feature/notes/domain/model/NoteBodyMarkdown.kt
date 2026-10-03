package com.singularity.todo.feature.notes.domain.model

import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * The only file in the notes domain that may import the richeditor library.
 *
 * The canonical body format is HTML, and the richeditor library speaks HTML natively.
 * Every other projection — plainText, wordCount, charCount, outgoingLinks — is plain
 * string/regex work in [NoteBody] and needs no UI dependency at all.
 *
 * The library knowledge is confined to the two boundary directions that genuinely need it:
 * markdown → HTML (via [markdownToHtml]), and HTML → markdown (via [NoteBody.toMarkdown]).
 * Neither happens on a keystroke, which is why markdown is a projection and not a stored
 * column.
 *
 * A codec interface would have exactly one implementation, which conflicts with two
 * independent constraints: the rich-editor skill deleted the analogous MarkdownHtmlPort,
 * and Rule of Three forbids single-implementation abstractions. A file is the honest
 * amount of structure. See ADR 2026-10-02-note-body-canonical-html.
 */
internal fun markdownToHtml(markdown: String): String = RichTextState().apply { setMarkdown(markdown) }.toHtml()

/** Projects the canonical HTML back to markdown. */
fun NoteBody.toMarkdown(): String = RichTextState().apply { setHtml(html) }.toMarkdown()
