package com.singularity.todo.feature.checklist

@JvmInline
value class ChecklistItemId private constructor(val value: String) {
    companion object {
        fun generate() =
            ChecklistItemId(
                com.singularity.todo.core.ids
                    .nextId(),
            )

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
    /**
     * Epoch millis when the row was created.
     *
     * Carried so that an undone delete puts back the row the user had: the row is
     * gone from the table, and a row written anew is a new row with a new creation
     * time. Null when the caller does not know it.
     */
    val createdAt: Long? = null,
    /**
     * The version of the row — it counts the completion toggles.
     *
     * Carried for the same reason as [createdAt]: a row written anew starts its
     * count over, and the toggles the user made before the delete are lost with it.
     * Null when the caller does not know it.
     */
    val rowVersion: Int? = null,
)
