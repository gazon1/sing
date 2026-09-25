package com.singularity.todo.feature.tags.domain.port

import com.singularity.todo.feature.tags.domain.model.CreateTagGroupInput
import com.singularity.todo.feature.tags.domain.model.TagGroup
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import com.singularity.todo.feature.tags.domain.model.UpdateTagGroupInput
import kotlinx.coroutines.flow.Flow

/**
 * Repository for tag group persistence and observation.
 *
 * All observation variants automatically re-subscribe when the active user changes.
 */
interface TagGroupRepository {

    /** Emits all tag groups for the current user. */
    fun observeAll(): Flow<List<TagGroup>>

    /** Emits a single tag group by ID. */
    fun observe(id: TagGroupId): Flow<TagGroup?>

    /** Gets a tag group by ID synchronously (or null if not found). */
    suspend fun get(id: TagGroupId): TagGroup?

    /** Creates a new tag group and returns it. */
    suspend fun create(input: CreateTagGroupInput): Result<TagGroup>

    /** Updates an existing tag group and returns the updated entity. */
    suspend fun update(input: UpdateTagGroupInput): Result<TagGroup>

    /** Soft-deletes a tag group and sets groupId=null on all its member tags. */
    suspend fun delete(id: TagGroupId): Result<Unit>

    /**
     * Returns the set of tag group IDs inherited by a project.
     */
    fun observeInheritedByProject(
        projectId: com.singularity.todo.feature.projects.domain.model.ProjectId,
    ): Flow<Set<TagGroupId>>

    /**
     * Replaces the set of tag groups inherited by a project.
     */
    suspend fun setInheritedForProject(
        projectId: com.singularity.todo.feature.projects.domain.model.ProjectId,
        groupIds: Set<TagGroupId>,
    ): Result<Unit>

    /**
     * Upserts a tag group from a remote sync event.
     * Does NOT emit repository-level change events — caller handles observability.
     * Used by pull handlers in [com.singularity.todo.core.sync.SyncBootstrapper].
     */
    suspend fun upsert(tagGroup: TagGroup): TagGroup
}
