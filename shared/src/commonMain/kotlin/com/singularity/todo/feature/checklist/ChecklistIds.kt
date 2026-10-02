package com.singularity.todo.feature.checklist

@JvmInline
value class ChecklistItemId private constructor(val value: String) {
    companion object {
        fun generate() = ChecklistItemId(com.singularity.todo.core.ids.nextId())
        fun fromString(v: String) = ChecklistItemId(v)
    }
}

data class ChecklistItem(
    val id: ChecklistItemId,
    val taskId: String,
    val title: String,
    val isCompleted: Boolean = false,
    val sortOrder: Int = 0,
    /** Who checked this item: "user" or "ai". Null for items created before this field was added. */
    val checkedBy: String? = null,
    /** Epoch millis when the item was last checked/unchecked. Null for items created before this field was added. */
    val checkedAt: Long? = null,
)
