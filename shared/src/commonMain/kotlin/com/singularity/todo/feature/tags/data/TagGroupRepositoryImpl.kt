package com.singularity.todo.feature.tags.data

import com.singularity.todo.core.database.ProjectDao
import com.singularity.todo.core.database.ProjectInheritedTagGroupDao
import com.singularity.todo.core.database.TagDao
import com.singularity.todo.core.database.TagGroupDao
import com.singularity.todo.core.database.TagGroupEntity
import com.singularity.todo.core.database.projectWithInheritance
import com.singularity.todo.core.database.toEpochMillis
import com.singularity.todo.core.database.toInstant
import com.singularity.todo.core.database.toInstantOrNull
import com.singularity.todo.core.ids.UserId
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
import kotlin.time.Clock
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.database.UnitOfWork

/**
 * Room-backed production [TagGroupRepository].
 */
class TagGroupRepositoryImpl(
    private val tagGroupDao: TagGroupDao,
    private val inheritedTagGroupDao: ProjectInheritedTagGroupDao,
    private val projectDao: ProjectDao,
    private val tagDao: TagDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
    private val syncRepository: SyncRepository,
    private val unitOfWork: UnitOfWork,
) : TagGroupRepository {

    // ─── Observation ────────────────────────────────────────────────────────────

    override fun observeAll(): Flow<List<TagGroup>> = currentUser.observeForCurrentUser { uid ->
        tagGroupDao.watchAll(uid.value).map { list ->
            list.map { it.toTagGroup() }
        }
    }

    // Scoped: the unscoped `watchById` returns another profile's tag group.
    override fun observe(id: TagGroupId): Flow<TagGroup?> = currentUser.observeForCurrentUser { uid ->
        tagGroupDao.watchByIdForUser(id.value, uid.value).map { it?.toTagGroup() }
    }

    override suspend fun get(id: TagGroupId): TagGroup? {
        val uid = currentUser.scopedUserId.value
        return tagGroupDao.getByIdForUser(id.value, uid.value)?.toTagGroup()
    }

    // ─── Write operations ───────────────────────────────────────────────────────

    override suspend fun create(input: CreateTagGroupInput): Result<TagGroup> = runCatchingCancellable {
        unitOfWork.write {
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
    }

    override suspend fun update(input: UpdateTagGroupInput): Result<TagGroup> = runCatchingCancellable {
        unitOfWork.write {
            val uid = currentUser.scopedUserId.value
            val existing = tagGroupDao.getByIdForUser(input.id.value, uid.value)
                ?: throw NoSuchElementException("TagGroup not found: ${input.id}")
            // DAO-level filter above is the first guard; explicit assertCanWrite is the
            // second (the entity stores the raw String column, hence the wrap).
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
    }

    override suspend fun delete(id: TagGroupId): Result<Unit> = runCatchingCancellable {
        unitOfWork.write {
            val uid = currentUser.scopedUserId.value
            val ts = clock.now().toEpochMillis()
            val rows = tagGroupDao.softDeleteForUser(id.value, ts, uid.value)
            require(rows > 0) { "TagGroup $id not found or not owned by current user" }

            // Read the members *before* releasing them: their rows change, so each one
            // has to be pushed to sync or the server keeps the tags in the dead group.
            val releasedTags = tagDao.listByGroupForUser(id.value, uid.value)
            tagDao.clearGroupForUser(id.value, ts, uid.value)
            releasedTags.forEach { tag -> syncRepository.enqueue(tag.toTag()) }

            // The join table has no deleted_at of its own and watchByProject does not
            // filter deleted groups, so a leftover row would keep resolving this group
            // into every project that inherited it.
            inheritedTagGroupDao.deleteByGroupForUser(id.value, uid.value)

            // Push the *real* trashed group, not a placeholder. The previous form
            // built `name = ""`, `color = 0` on the assumption that the sync handler
            // only reads docType + syncId — but toJson() serialises the whole model,
            // so the server received a tag group with an empty name. Deletion
            // propagates as state (`deletedAt`), same as every other entity.
            val row = tagGroupDao.getByIdForUser(id.value, uid.value)
                ?: throw IllegalStateException("TagGroup $id vanished between soft delete and sync")
            syncRepository.enqueue(row.toTagGroup())
        }
    }

    // ─── Inheritance ────────────────────────────────────────────────────────────

    override fun observeInheritedByProject(projectId: ProjectId): Flow<Set<TagGroupId>> =
        inheritedTagGroupDao.watchByProject(projectId.value, currentUser.scopedUserId.value.value).map { list ->
            list.mapTo(LinkedHashSet()) { TagGroupId.fromString(it) }
        }

    override suspend fun setInheritedForProject(projectId: ProjectId, groupIds: Set<TagGroupId>): Result<Unit> =
        runCatchingCancellable {
            val uid = currentUser.scopedUserId.value.value
            unitOfWork.write {
                require(inheritedTagGroupDao.isProjectOwnedBy(projectId.value, uid)) {
                    "Project $projectId not found or not owned by current user"
                }
                // Delete all existing inheritance rows for this project, then re-insert.
                // Room DAO methods each run in their own implicit transaction, so a crash between
                // the delete and the insert leaves the inheritance empty — visible immediately,
                // not silent. The atomic alternative (@RawQuery multi-statement) is not available
                // without room-ktx. If this ever becomes a real problem, add a new DAO method
                // annotated @Transaction with a @Query that uses a CTE or MERGE statement.
                inheritedTagGroupDao.deleteAllForUser(projectId.value, uid)
                for (groupId in groupIds) {
                    inheritedTagGroupDao.insertForUser(projectId.value, groupId.value, uid)
                }
                // Inheritance is part of the *project* document, not a tag group's: the
                // server's allowlist lists `project.inheritedTagGroupIds`, and no DocType
                // describes the join table. So the project is what has to be patched, and
                // its `updated_at` stamped, or the change never leaves this device while
                // looking locally applied.
                projectDao.touchUpdatedAtForUser(projectId.value, clock.now().toEpochMilliseconds(), uid)
                // Inlined rather than factored into an `enqueueProject()` helper.
                //
                // `SyncedWriteEnqueuesTest` recognises an enqueue by its spelling —
                // `.enqueue(`, `enqueueFresh(`, `enqueuePatch(` — so a helper with any
                // other name makes this method look like a write with no patch. Adding a
                // fourth spelling to that list would have made the test pass and left the
                // rule's premise ("the enqueue is in this body") quietly untrue. The
                // general fix is #229: decide by call graph rather than by name.
                //
                // The re-read carries the inheritance this method just wrote. A patch
                // serialised without it asserts the project inherits nothing, which the
                // server would apply, erasing the change made a line above.
                val project = projectWithInheritance(projectDao, inheritedTagGroupDao, projectId.value, uid)
                if (project != null) {
                    syncRepository.enqueue(project)
                }
            }
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
