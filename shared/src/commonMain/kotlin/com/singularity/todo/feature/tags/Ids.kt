package com.singularity.todo.feature.tags

import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.DocType
import com.singularity.todo.core.sync.Hlc
import com.singularity.todo.core.sync.SyncableEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
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
    // ─── Sync fields ───────────────────────────────────────────────────────────
    val serverVersion: Long = 0,
    val hlc: Hlc? = null,
) : SyncableEntity {
    // SyncableEntity implementation
    override val syncId: String get() = id.value
    override val docType: DocType get() = DocType.Tag
    override val syncServerVersion: Long get() = serverVersion
    override val syncHlc: Hlc? get() = hlc

    override fun toJson(): JsonObject {
        @Suppress("UNCHECKED_CAST")
        val ser = serializer<Tag>()
        return StableJson.encodeToJsonElement(ser, this) as JsonObject
    }
}
