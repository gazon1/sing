package com.singularity.todo.feature.notes

/**
 * Pure formatters for notes — testable without Compose runtime.
 */
fun formatNoteAiResult(result: NoteAiResult): String = when (result) {
    is NoteAiResult.Improved -> "Note improved!\n\nTitle: ${result.title}"
    is NoteAiResult.Error -> "Error: ${result.message}"
}

fun formatSummarizeResult(result: SummarizeResult): String = when (result) {
    is SummarizeResult.Ok -> "Summary:\n${result.summary}"
    is SummarizeResult.Error -> "Summarize failed: ${result.message}"
}

fun formatExtractActionsResult(result: ExtractActionsResult): String = when (result) {
    is ExtractActionsResult.Ok -> if (result.actions.isEmpty()) {
        "No actions found in this note."
    } else {
        "Actions (${result.actions.size}):\n${result.actions.joinToString("\n") { "- $it" }}"
    }
    is ExtractActionsResult.Error -> "Extract actions failed: ${result.message}"
}

fun formatSuggestTagsResult(result: SuggestTagsResult): String = when (result) {
    is SuggestTagsResult.Ok -> if (result.tags.isEmpty()) {
        "No tags suggested."
    } else {
        "Suggested tags:\n${result.tags.joinToString(", ") { "#$it" }}"
    }
    is SuggestTagsResult.Error -> "Suggest tags failed: ${result.message}"
}

/**
 * Strips Markdown syntax from [markdown] and returns a plain text preview,
 * truncated to [maxChars] characters. Used by [NoteCardContent] to show
 * a readable snippet instead of raw Markdown.
 */
fun extractPreviewText(markdown: String?, maxChars: Int = 120): String {
    if (markdown.isNullOrBlank()) return ""
    val stripped = markdown
        .replace(Regex("""#{1,6}\s+"""), "") // headings
        .replace(Regex("""\*\*(.+?)\*\*"""), "$1") // bold
        .replace(Regex("""\*(.+?)\*"""), "$1") // italic
        .replace(Regex("""__(.+?)__"""), "$1") // bold alt
        .replace(Regex("""_(.+?)_"""), "$1") // italic alt
        .replace(Regex("""~~(.+?)~~"""), "$1") // strikethrough
        .replace(Regex("""`{1,3}[^`]*`{1,3}"""), "") // code
        .replace(Regex("""\[([^]]+)]\([^)]+\)"""), "$1") // links
        .replace(Regex("""!\[[^]]*]\([^)]+\)"""), "") // images
        .replace(Regex("""^\s*[-*+]\s+""", RegexOption.MULTILINE), "") // list bullets
        .replace(Regex("""^\s*\d+\.\s+""", RegexOption.MULTILINE), "") // numbered lists
        .replace(Regex("""^\s*>\s+""", RegexOption.MULTILINE), "") // blockquotes
        .replace(Regex("""\n{2,}"""), " ") // multiple newlines
        .replace(Regex("""\n"""), " ") // single newlines
        .trim()
    return if (stripped.length <= maxChars) stripped else stripped.take(maxChars).removeSuffix(" ") + "…"
}
