package com.singularity.todo.feature.notes

/**
 * Pure formatters for notes — testable without Compose runtime.
 */
fun formatNoteAiResult(result: NoteAiResult): String =
    when (result) {
        is NoteAiResult.Improved -> "Note improved!\n\nTitle: ${result.title}"
        is NoteAiResult.Error -> "Error: ${result.message}"
    }

fun formatSummarizeResult(result: SummarizeResult): String =
    when (result) {
        is SummarizeResult.Ok -> "Summary:\n${result.summary}"
        is SummarizeResult.Error -> "Summarize failed: ${result.message}"
    }

fun formatExtractActionsResult(result: ExtractActionsResult): String =
    when (result) {
        is ExtractActionsResult.Ok -> {
            if (result.actions.isEmpty()) {
                "No actions found in this note."
            } else {
                "Actions (${result.actions.size}):\n${result.actions.joinToString("\n") { "- $it" }}"
            }
        }

        is ExtractActionsResult.Error -> {
            "Extract actions failed: ${result.message}"
        }
    }

fun formatSuggestTagsResult(result: SuggestTagsResult): String =
    when (result) {
        is SuggestTagsResult.Ok -> {
            if (result.tags.isEmpty()) {
                "No tags suggested."
            } else {
                "Suggested tags:\n${result.tags.joinToString(", ") { "#$it" }}"
            }
        }

        is SuggestTagsResult.Error -> {
            "Suggest tags failed: ${result.message}"
        }
    }
