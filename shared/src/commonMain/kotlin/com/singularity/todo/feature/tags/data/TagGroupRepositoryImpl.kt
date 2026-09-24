package com.singularity.todo.feature.tags.data

import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.domain.model.CreateTagGroupInput
import com.singularity.todo.feature.tags.domain.model.TagGroup
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import com.singularity.todo.feature.tags.domain.model.UpdateTagGroupInput
import com.singularity.todo.feature.tags.domain.port.TagGroupRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Room-backed production [TagGroupRepository].
 *
 * ## DAO dependencies (added in Migration 19→20)
 *
 * This implementation requires the following DAO methods added in MR-9:
 * - `TagGroupDao.watchAll(userId)` — observe all tag groups
 * - `TagGroupDao.watchById(id)` — observe single tag group
 * - `TagGroupDao.getByIdForUser(id, userId)` — get single tag group
 * - `TagGroupDao.upsert(entity)` — create/update
 * - `TagGroupDao.softDelete(id, ts)` — soft delete
 * - `ProjectInheritedTagGroupCrossRefDao.observeByProject(projectId)` — inherited group IDs
 * - `ProjectInheritedTagGroupCrossRefDao.replace(projectId, groupIds)` — set inherited groups
 *
 * Currently returns [Result.failure] with [UnsupportedOperationException] for all DAO-dependent
 * operations until MR-9. This allows the app to compile and run (tag groups simply won't sync)
 * without crashing.
 */
class TagGroupRepositoryImpl(
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
) : TagGroupRepository {

    // ─── Stub flows (replace with DAO-backed flows after MR-9) ───────────────────

    override fun observeAll(): Flow<List<TagGroup>> = emptyFlow()

    override fun observe(id: TagGroupId): Flow<TagGroup?> = emptyFlow()

    override suspend fun get(id: TagGroupId): TagGroup? = null

    override fun observeInheritedByProject(projectId: ProjectId): Flow<Set<TagGroupId>> = emptyFlow()

    // ─── Write operations (replace with DAO after MR-9) ─────────────────────────

    override suspend fun create(input: CreateTagGroupInput): Result<TagGroup> {
        return Result.failure(
            UnsupportedOperationException(
                "TagGroupRepositoryImpl.create() requires TagGroupDao from MR-9 migration"
            )
        )
    }

    override suspend fun update(input: UpdateTagGroupInput): Result<TagGroup> {
        return Result.failure(
            UnsupportedOperationException(
                "TagGroupRepositoryImpl.update() requires TagGroupDao from MR-9 migration"
            )
        )
    }

    override suspend fun delete(id: TagGroupId): Result<Unit> {
        return Result.failure(
            UnsupportedOperationException(
                "TagGroupRepositoryImpl.delete() requires TagGroupDao from MR-9 migration"
            )
        )
    }

    override suspend fun setInheritedForProject(projectId: ProjectId, groupIds: Set<TagGroupId>): Result<Unit> {
        return Result.failure(
            UnsupportedOperationException(
                "TagGroupRepositoryImpl.setInheritedForProject() requires ProjectInheritedTagGroupCrossRefDao from MR-9 migration"
            )
        )
    }

    override suspend fun upsert(tagGroup: TagGroup): TagGroup {
        throw UnsupportedOperationException(
            "TagGroupRepositoryImpl.upsert() requires TagGroupDao from MR-9 migration"
        )
    }
}
