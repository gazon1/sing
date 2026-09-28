package com.singularity.todo.feature.tags

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.TagEntity
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.data.TagsRepositoryImpl
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

/**
 * Read-isolation for tag observation.
 *
 * `TagsRepositoryImpl.observeTag` used the unscoped `TagDao.watchById`, so it returned a
 * tag belonging to a different profile — and, because that query also lacks the
 * `deleted_at` filter its scoped sibling has, a soft-deleted one. This is the same defect
 * class as the `TaskDao.getById` leak closed in `2026-09-27-write-layer-soundness`
 * (ledger #1).
 */
class TagsReadIsolationTest {

    private val currentUserId = UserId("u1")
    private val otherUserId = UserId("u2")

    private suspend fun setup() = FakeAppDatabase().also { db ->
        db.tagDao().upsert(tag("t-mine", currentUserId, deletedAt = null))
        db.tagDao().upsert(tag("t-theirs", otherUserId, deletedAt = null))
        db.tagDao().upsert(tag("t-deleted", currentUserId, deletedAt = 1_000L))
    }

    private fun tag(id: String, userId: UserId, deletedAt: Long?): TagEntity = TagEntity(
        id = id,
        userId = userId.value,
        name = id,
        color = 0,
        sortOrder = 0,
        createdAt = 1_000_000_000L,
        updatedAt = 1_000_000_000L,
        deletedAt = deletedAt,
    )

    private fun repository(db: FakeAppDatabase) = TagsRepositoryImpl(
        tagDao = db.tagDao(),
        clock = Clock.System,
        currentUser = FakeProfileAwareCurrentUser(
            authRepository = FakeAuthRepository(Session.Anonymous(currentUserId)),
        ),
        syncRepository = FakeSyncRepository(),
    )

    @Test
    fun observeTag_returnsOwnTag() = runTest {
        val db = setup()
        val observed = repository(db).observeTag(TagId.fromString("t-mine")).first()
        assertEquals(TagId.fromString("t-mine"), observed?.id)
    }

    @Test
    fun observeTag_doesNotLeakAnotherUsersTag() = runTest {
        val db = setup()
        val observed = repository(db).observeTag(TagId.fromString("t-theirs")).first()
        assertNull(observed, "a tag owned by another profile must not be observable")
    }

    @Test
    fun observeTag_doesNotLeakSoftDeletedTag() = runTest {
        val db = setup()
        val observed = repository(db).observeTag(TagId.fromString("t-deleted")).first()
        assertNull(observed, "a soft-deleted tag must not be observable")
    }
}
