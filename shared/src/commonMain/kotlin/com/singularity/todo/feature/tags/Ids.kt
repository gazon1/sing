package com.singularity.todo.feature.tags

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
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
    /**
     * Dead schema — replaced by `groupId` referencing [com.singularity.todo.feature.tags.domain.model.TagGroup] in MR-3.
     *
     * This field is never read, never written, and never cascaded.
     * The entity layer explicitly discards it in both directions.
     *
     * @see 2026-09-18-tag-groups-inheritance
     */
    @Deprecated(
        message = "Dead schema — replaced by TagGroup in MR-3",
        replaceWith = ReplaceWith("groupId"),
    )
    val parentId: TagId? = null,
    val sortOrder: Int = 0,
    val deletedAt: Instant? = null,
    val userId: String,
)

data class CreateTagInput(val name: String, val color: Int, val userId: String)
