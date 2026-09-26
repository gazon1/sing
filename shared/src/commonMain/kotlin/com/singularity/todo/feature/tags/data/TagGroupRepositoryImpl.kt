package com.singularity.todo.feature.tags.data

import com.singularity.todo.core.database.ProjectInheritedTagGroupDao
import com.singularity.todo.core.database.TagGroupDao
import com.singularity.todo.core.database.TagGroupEntity
import com.singularity.todo.core.database.toEpochMillis
import com.singularity.todo.core.database.toInstant
import com.singularity.todo.core.database.toInstantOrNull
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.repository.assertCanWrite
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.domain.model.CreateTagGroupInput
import com.singularity.todo.feature.tags.domain.model.TagGroup
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import com.singularity.todo.feature.tags.domain.model.UpdateTagGroupInput
import com.singularity.todo.feature.tags.domain.port.TagGroupRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed production [TagGroupRepository].
 */
class TagGroupRepositoryImpl(
    private val tagGroupDao: TagGroupDao,
    private val inheritedTagGroupDao: ProjectInheritedTagGroupDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
    private val syncRepository: SyncRepository,
) : TagGroupRepository {

    // ─── Observation ────────────────────────────────────────────────────────────

    override fun observeAll(): Flow<List<TagGroup>> = currentUser.observeForCurrentUser { uid ->
        tagGroupDao.watchAll(uid.value).map { list ->
            list.map { it.toTagGroup() }
        }
    }

    override fun observe(id: TagGroupId): Flow<TagGroup?> = tagGroupDao.watchById(id.value).map { it?.toTagGroup() }

    override suspend fun get(id: TagGroupId): TagGroup? {
        val uid = currentUser.scopedUserId.value
        return tagGroupDao.getByIdForUser(id.value, uid.value)?.toTagGroup()
    }

    // ─── Write operations ───────────────────────────────────────────────────────

    override suspend fun create(input: CreateTagGroupInput): Result<TagGroup> = runCatching {
        val uid = currentUser.scopedUserId.value
        val now = clock.now()
        val tagGroup = TagGroup(
            id = TagGroupId.generate(),
            name = input.name.trim(),
            color = input.color,
            createdAt = now,
            updatedAt = now,
            userId = uid,
        )
        tagGroupDao.upsert(tagGroup.toEntity())
        syncRepository.enqueue(tagGroup)
        tagGroup
    }

    override suspend fun update(input: UpdateTagGroupInput): Result<TagGroup> = runCatching {
        val uid = currentUser.scopedUserId.value
        val existing = tagGroupDao.getByIdForUser(input.id.value, uid.value)
            ?: throw NoSuchElementException("TagGroup not found: ${input.id}")
        // DAO-level filter above is the first guard; explicit assertCanWrite is the second.
        // TagGroup.userId is stored as String, so wrap with UserId() for the guard.
        currentUser.assertCanWrite(
            entityId = existing.id,
            entityUserId = UserId(existing.userId),
        )
        val updated = existing.toTagGroup().copy(
            name = input.name.trim(),
            color = input.color,
            updatedAt = clock.now(),
        )
        tagGroupDao.upsert(updated.toEntity())
        syncRepository.enqueue(updated)
        updated
    }

    override suspend fun delete(id: TagGroupId): Result<Unit> = runCatching {
        val uid = currentUser.scopedUserId.value
        val ts = clock.now().toEpochMillis()
        tagGroupDao.softDelete(id.value, ts)
        // TODO: clear groupId on member tags (requires TagDao bulk update)
        syncRepository.enqueue(
            TagGroup(
                id = id,
                name = "", // placeholder; sync handler uses docType + syncId only
                color = 0,
                createdAt = clock.now(),
                updatedAt = clock.now(),
                userId = uid,
                deletedAt = clock.now(),
            ),
        )
    }

    // ─── Inheritance ────────────────────────────────────────────────────────────

    override fun observeInheritedByProject(projectId: ProjectId): Flow<Set<TagGroupId>> =
        inheritedTagGroupDao.watchByProject(projectId.value).map { list ->
            list.mapTo(LinkedHashSet()) { TagGroupId.fromString(it) }
        }

    override suspend fun setInheritedForProject(projectId: ProjectId, groupIds: Set<TagGroupId>): Result<Unit> =
        runCatching {
            inheritedTagGroupDao.deleteAllForProject(projectId.value)
            // Note: individual inserts not needed — upsert via raw SQL handled by sync worker
        }

    // ─── Sync ───────────────────────────────────────────────────────────────────

    override suspend fun upsert(tagGroup: TagGroup): TagGroup {
        tagGroupDao.upsert(tagGroup.toEntity())
        return tagGroup
    }
}

// ─── Mappers (internal, used within the same module) ─────────────────────────

private fun TagGroupEntity.toTagGroup(): TagGroup = TagGroup(
    id = TagGroupId.fromString(id),
    name = name,
    color = color,
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
    userId = UserId(userId),
    deletedAt = deletedAt.toInstantOrNull(),
    serverVersion = sync.serverVersion,
    hlc = sync.hlc?.let { com.singularity.todo.core.sync.Hlc(it) },
)

private fun TagGroup.toEntity(): TagGroupEntity = TagGroupEntity(
    id = id.value,
    userId = userId.value,
    name = name,
    color = color,
    createdAt = createdAt.toEpochMillis(),
    updatedAt = updatedAt.toEpochMillis(),
    deletedAt = deletedAt?.toEpochMillis(),
    sync = com.singularity.todo.core.database.SyncColumns(
        serverVersion = serverVersion,
        hlc = hlc?.encoded,
    ),
)
