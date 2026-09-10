package com.singularity.todo.feature.tags

import kotlin.time.Instant

@JvmInline
value class TagId(val value: String) {
    companion object {
        fun generate() = TagId(com.singularity.todo.core.ids.nextId())
        fun fromString(value: String) = TagId(value)
    }
}

data class Tag(
    val id: TagId,
    val name: String,
    val color: Int, // ARGB
    val createdAt: Instant,
    val updatedAt: Instant,
    val parentId: TagId? = null,
    val sortOrder: Int = 0,
    val deletedAt: Instant? = null,
    val userId: String
)

data class CreateTagInput(
    val name: String,
    val color: Int,
    val userId: String
)
