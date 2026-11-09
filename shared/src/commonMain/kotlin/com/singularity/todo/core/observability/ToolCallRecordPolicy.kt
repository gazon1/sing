package com.singularity.todo.core.observability

/**
 * The window the tool-call records live in, in one place.
 *
 * The report reads its window from `config/docs/tool-call-records.json`, and each
 * platform's writer deletes on its own schedule. Two writers and two constants meant
 * two places to forget: the day one of them drifted, the report described a window
 * that no longer existed — the records were gone before the report stopped counting
 * them, and the report said so with a number nobody could check.
 *
 * So the window is one constant, in common code, that both writers use and the policy
 * test checks against the file. The Android writer's own copy of that check was the
 * second place the same fact was asserted; with the constant in common code there is
 * one fact, one assertion, and one tier that owns the scenario.
 */
object ToolCallRecordPolicy {
    /** How many days of records are kept. */
    const val RETENTION_DAYS = 30
}
