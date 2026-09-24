package com.singularity.todo.feature.tags.domain.model

import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.DocType
import com.singularity.todo.core.sync.Hlc
import com.singularity.todo.core.sync.SyncableEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.time.Instant

/**
 * Inline value class for tag group IDs.
 */
@Serializable
@JvmInline
value class TagGroupId(val value: String) {
    companion object {
        fun generate() = TagGroupId(com.singularity.todo.core.ids.nextId())
        fun fromString(value: String) = TagGroupId(value)
    }
}

/**
 * A named group of tags with a color.
 *
 * Tag groups allow users to organize tags into named collections (e.g. :work:, :home:, :urgent:).
 * Tags inherit the group they belong to. Projects can inherit entire tag groups.
 *
 * @param id Unique identifier.
 * @param name Group name (e.g. "work", "urgent"). Displayed as ":name:".
 * @param color ARGB color for the group badge.
 * @param createdAt Creation timestamp.
 * @param updatedAt Last modification timestamp.
 * @param userId Owner user ID.
 * @param serverVersion Sync server version.
 * @param hlc Hybrid Logical Clock for sync ordering.
 */
@Serializable
data class TagGroup(
    val id: TagGroupId,
    val name: String,
    val color: Int, // ARGB
    val createdAt: Instant,
    val updatedAt: Instant,
    val userId: String,
    // ─── Sync fields ───────────────────────────────────────────────────────────
    val serverVersion: Long = 0,
    val hlc: Hlc? = null,
) : SyncableEntity {
    // SyncableEntity implementation
    override val syncId: String get() = id.value
    override val docType: DocType get() = DocType.TagGroup
    override val syncServerVersion: Long get() = serverVersion
    override val syncHlc: Hlc? get() = hlc

    override fun toJson(): JsonObject {
        @Suppress("UNCHECKED_CAST")
        val ser = serializer<TagGroup>()
        return StableJson.encodeToJsonElement(ser, this) as JsonObject
    }
}

/**
 * Input for creating a new tag group.
 */
data class CreateTagGroupInput(
    val name: String,
    val color: Int, // ARGB
)

/**
 * Input for updating an existing tag group.
 */
data class UpdateTagGroupInput(
    val id: TagGroupId,
    val name: String,
    val color: Int,
)
