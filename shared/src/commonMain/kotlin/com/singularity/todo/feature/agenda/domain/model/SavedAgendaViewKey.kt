package com.singularity.todo.feature.agenda.domain.model

/**
 * Composite lookup key for in-memory stores that need a single value-type key
 * combining both [userId] and [viewId].
 *
 * Format: `"$userId:$viewId"`
 */
@JvmInline
value class SavedAgendaViewKey(private val raw: String) {
    companion object {
        fun of(userId: String, viewId: String): SavedAgendaViewKey =
            SavedAgendaViewKey("$userId:$viewId")
    }

    override fun toString(): String = raw
}
