package com.singularity.todo.feature.checklist

import java.util.UUID

@JvmInline
value class ChecklistItemId private constructor(val value: String) {
    companion object {
        fun generate() = ChecklistItemId(UUID.randomUUID().toString())
        fun fromString(v: String) = ChecklistItemId(v)
    }
}

data class ChecklistItem(
    val id: ChecklistItemId,
    val taskId: String,
    val title: String,
    val isCompleted: Boolean = false,
    val sortOrder: Int = 0,
)
