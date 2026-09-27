package com.singularity.todo.feature.projects.domain.model

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.DocType
import com.singularity.todo.core.sync.Hlc
import com.singularity.todo.core.sync.SyncableEntity
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.time.Instant

@Serializable
@JvmInline
value class ProjectId(val value: String) {
    companion object {
        fun generate() = ProjectId(com.singularity.todo.core.ids.nextId())
        fun fromString(value: String) = ProjectId(value)
    }
}

/**
 * `@Serializable` is load-bearing: [toJson] resolves `serializer<Project>()`.
 * Without it every enqueue throws, `runCatchingResult` swallows it, and projects
 * silently never reach the sync outbox. Same defect as Note, fixed 2026-09-27.
 */
@Serializable
data class Project(
    val id: ProjectId,
    val name: String,
    val color: Int, // ARGB
    val icon: String? = null,
    val description: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val isDefault: Boolean = false,
    val dueDate: kotlinx.datetime.LocalDate? = null,
    val team: String? = null,
    val isDeleted: Boolean = false,
    val deletedAt: Instant? = null,
    val parentId: ProjectId? = null,
    val sortOrder: Int = 0,
    val idempotencyKey: String? = null,
    val externalId: String? = null,
    val userId: UserId,
    /**
     * Tag group IDs inherited by this project.
     *
     * Stored in the [ProjectInheritedTagGroupCrossRef] join table, not on [ProjectEntity].
     * Loaded and set by [com.singularity.todo.feature.tags.domain.port.TagGroupRepository.setInheritedForProject].
     *
     * When non-empty, all tags inside those groups are visible to tasks belonging to this project.
     * Resolved at runtime by [com.singularity.todo.feature.tags.domain.usecase.EffectiveTagsResolver].
     */
    val inheritedTagGroupIds: Set<TagGroupId> = emptySet(),
    // ─── Sync fields ───────────────────────────────────────────────────────────
    val serverVersion: Long = 0,
    val hlc: Hlc? = null,
) : SyncableEntity {
    // SyncableEntity implementation
    override val syncId: String get() = id.value
    override val docType: DocType get() = DocType.Project
    override val syncServerVersion: Long get() = serverVersion
    override val syncHlc: Hlc? get() = hlc

    override fun toJson(): JsonObject {
        @Suppress("UNCHECKED_CAST")
        val ser = serializer<Project>()
        return StableJson.encodeToJsonElement(ser, this) as JsonObject
    }
}

/** Domain projection of [Project] with task counts, used by [com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsViewModel] UI state. */
data class ProjectWithCounts(val project: Project, val totalCount: Int, val completedCount: Int)

data class CreateProjectInput(
    val name: String,
    val color: Int,
    val icon: String? = null,
    val description: String? = null,
    val parentId: ProjectId? = null,
)
