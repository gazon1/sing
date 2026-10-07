@file:Suppress("NoDirectClockSystem")

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tags

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.database.TagEntity
import com.singularity.todo.core.database.TagGroupEntity
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.feature.tags.data.TagGroupRepositoryImpl
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import com.singularity.todo.test.fakes.FakeUnitOfWork

/**
 * Deleting a tag group must release what pointed at it.
 *
 * The group is soft-deleted, so its row stays. That made it survivable to leave the
 * references behind — until it wasn't: member tags kept a `group_id` naming a group the
 * user could no longer see, and `project_tag_groups` kept the group id, which
 * `observeInheritedByProject` does not filter against `deleted_at`. A deleted group
 * therefore kept contributing its tags to every project that inherited it.
 *
 * The join table is the half that actually changed user-visible behaviour, so it is
 * covered first; the tag release is asserted through the same delete.
 */
@Tag("fast")
class TagGroupDeleteCascadeTest {

    private val userId = UserId("u1")

    private fun group(id: String): TagGroupEntity = TagGroupEntity(
        id = id,
        userId = userId.value,
        name = id,
        color = 0,
        createdAt = 1_000_000_000L,
        updatedAt = 1_000_000_000L,
        deletedAt = null,
    )

    private fun project(id: String): ProjectEntity = ProjectEntity(
        id = id,
        userId = userId.value,
        name = id,
        color = 0,
        icon = null,
        description = null,
        createdAt = 1_000_000_000L,
        updatedAt = 1_000_000_000L,
        dueDate = null,
        team = null,
        isDeleted = false,
        deletedAt = null,
        parentId = null,
        externalId = null,
    )

    private fun tag(id: String, groupId: String?): TagEntity = TagEntity(
        id = id,
        userId = userId.value,
        name = id,
        color = 0,
        sortOrder = 0,
        createdAt = 1_000_000_000L,
        updatedAt = 1_000_000_000L,
        deletedAt = null,
        groupId = groupId,
    )

    private fun repository(db: FakeAppDatabase, sync: FakeSyncRepository = FakeSyncRepository()) =
        TagGroupRepositoryImpl(
            tagGroupDao = db.tagGroupDao(),
            inheritedTagGroupDao = db.projectInheritedTagGroupDao(),
            tagDao = db.tagDao(),
            clock = Clock.System,
            currentUser = FakeProfileAwareCurrentUser(
                authRepository = FakeAuthRepository(Session.Anonymous(userId)),
            ),
            syncRepository = sync,
            unitOfWork = FakeUnitOfWork(),
        )

    @Test
    fun `delete releases member tags from the group`() = runTest {
        val db = FakeAppDatabase()
        db.tagGroupDao().upsert(group("g1"))
        db.tagDao().upsert(tag("t-in", groupId = "g1"))
        db.tagDao().upsert(tag("t-out", groupId = null))

        repository(db).delete(TagGroupId.fromString("g1")).getOrThrow()

        assertNull(db.tagDao().getByIdForUser("t-in", userId.value)?.groupId)
        assertNull(db.tagDao().getByIdForUser("t-out", userId.value)?.groupId)
    }

    @Test
    fun `delete leaves the group row soft-deleted so sync can push it`() = runTest {
        val db = FakeAppDatabase()
        db.tagGroupDao().upsert(group("g1"))

        repository(db).delete(TagGroupId.fromString("g1")).getOrThrow()

        // Deletion propagates as state, not as a row removal — the trashed group is what
        // gets enqueued, so the row has to still be there. (Asserted on the DAO because
        // FakeTagGroupDao.getByIdForUser does not filter deletedAt the way the real one does.)
        assertTrue(db.tagGroupDao().getByIdForUser("g1", userId.value)?.deletedAt != null)
    }

    @Test
    fun `delete drops project inheritance rows for the group`() = runTest {
        val db = FakeAppDatabase()
        db.tagGroupDao().upsert(group("g1"))
        db.tagGroupDao().upsert(group("g2"))
        db.projectDao().upsert(project("p1"))
        db.projectInheritedTagGroupDao().insertForUser("p1", "g1", userId.value)
        db.projectInheritedTagGroupDao().insertForUser("p1", "g2", userId.value)

        repository(db).delete(TagGroupId.fromString("g1")).getOrThrow()

        val inherited = db.projectInheritedTagGroupDao().watchByProject("p1", userId.value).first()
        assertEquals(listOf("g2"), inherited)
    }

    @Test
    fun `delete pushes released tags to sync so the server converges`() = runTest {
        val db = FakeAppDatabase()
        val sync = FakeSyncRepository()
        db.tagGroupDao().upsert(group("g1"))
        db.tagDao().upsert(tag("t-in", groupId = "g1"))

        repository(db, sync).delete(TagGroupId.fromString("g1")).getOrThrow()

        // The group plus every released member: without the tags, the server would keep
        // them inside a group this device has already released them from.
        assertEquals(2, sync.enqueuedEntities.size)
    }

    @Test
    fun `delete of a group the user does not own fails`() = runTest {
        val db = FakeAppDatabase()
        db.tagGroupDao().upsert(
            group("g-theirs").copy(userId = UserId("u2").value),
        )
        db.tagDao().upsert(tag("t-theirs", groupId = "g-theirs").copy(userId = UserId("u2").value))

        val result = repository(db).delete(TagGroupId.fromString("g-theirs"))

        assertTrue(result.isFailure)
        assertEquals("g-theirs", db.tagDao().getByIdForUser("t-theirs", UserId("u2").value)?.groupId)
    }
}
