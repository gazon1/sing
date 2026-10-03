@file:Suppress("NoDirectClockSystem")

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tags

import com.singularity.todo.core.auth.Session
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

/**
 * Read-isolation for tag-group observation. Same defect class as `TagsReadIsolationTest`
 * and the `TaskDao.getById` leak closed in `2026-09-27-write-layer-soundness` (ledger #1).
 */
class TagGroupReadIsolationTest {

    private val currentUserId = UserId("u1")
    private val otherUserId = UserId("u2")

    private suspend fun setup() = FakeAppDatabase().also { db ->
        db.tagGroupDao().upsert(group("g-mine", currentUserId))
        db.tagGroupDao().upsert(group("g-theirs", otherUserId))
    }

    private fun group(id: String, userId: UserId): TagGroupEntity = TagGroupEntity(
        id = id,
        userId = userId.value,
        name = id,
        color = 0,
        createdAt = 1_000_000_000L,
        updatedAt = 1_000_000_000L,
        deletedAt = null,
    )

    private fun repository(db: FakeAppDatabase) = TagGroupRepositoryImpl(
        tagGroupDao = db.tagGroupDao(),
        inheritedTagGroupDao = db.projectInheritedTagGroupDao(),
        tagDao = db.tagDao(),
        clock = Clock.System,
        currentUser = FakeProfileAwareCurrentUser(
            authRepository = FakeAuthRepository(Session.Anonymous(currentUserId)),
        ),
        syncRepository = FakeSyncRepository(),
    )

    @Test
    fun observe_returnsOwnGroup() = runTest {
        val db = setup()
        val observed = repository(db).observe(TagGroupId.fromString("g-mine")).first()
        assertEquals(TagGroupId.fromString("g-mine"), observed?.id)
    }

    @Test
    fun observe_doesNotLeakAnotherUsersGroup() = runTest {
        val db = setup()
        val observed = repository(db).observe(TagGroupId.fromString("g-theirs")).first()
        assertNull(observed, "a tag group owned by another profile must not be observable")
    }
}
