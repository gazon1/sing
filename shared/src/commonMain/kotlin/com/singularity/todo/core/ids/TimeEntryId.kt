package com.singularity.todo.core.ids

import kotlinx.serialization.Serializable

/**
 * Identity for a [com.singularity.todo.feature.timetracking.TimeEntry].
 */
@Serializable
@JvmInline
value class TimeEntryId(val value: String) {
    companion object {
        fun generate() = TimeEntryId(nextId())
        fun fromString(value: String) = TimeEntryId(value)
    }
}
