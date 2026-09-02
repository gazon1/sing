package com.singularity.todo.feature.tags

import java.util.UUID

@JvmInline
value class TagId(val value: String) {
    companion object {
        fun generate() = TagId(UUID.randomUUID().toString())
        fun fromString(value: String) = TagId(value)
    }
}

data class Tag(
    val id: TagId,
    val name: String,
    val color: Int, // ARGB
    val createdAt: kotlinx.datetime.Instant,
    val updatedAt: kotlinx.datetime.Instant,
    val parentId: TagId? = null,
    val sortOrder: Int = 0,
    val deletedAt: kotlinx.datetime.Instant? = null,
    val userId: String
)

data class CreateTagInput(
    val name: String,
    val color: Int,
    val userId: String
)
