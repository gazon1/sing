package com.singularity.todo.core.ui.components

/**
 * Inline-edit state machine for one field on a read-only screen.
 *
 * `View` — the field renders as plain text.
 * `Edit` — the field is in editing mode with [draft] as the current input.
 *
 * Pure data, no Compose runtime — testable without UI harness.
 */
sealed interface FieldMode {
    data object View : FieldMode
    data class Edit(val draft: String) : FieldMode
}

