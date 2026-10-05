@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.checklist

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.checklist.data.ChecklistRepositoryImpl
import com.singularity.todo.feature.checklist.domain.port.ChecklistRepository
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ordering behaviour of [ChecklistRepositoryImpl], tested against the real
 * implementation rather than the fake.
 *
 * The existing four checklist tests in `TaskLifecycleIntegrationTest` exercise
 * `FakeChecklistRepository`, and they never assert an order — they check that
 * two added items produce `size == 2`. The ordering rule itself was untested,
 * and untested is how the defect below survived: `addItem` writes
 * `sortOrder = 0` for every item it creates, while `createBatch` in the same
 * class assigns `index`. The DAO reads them back with
 * `ORDER BY sort_order ASC`.
 *
 * That combination is not visibly broken on a fresh database, and that is the
 * trap. SQLite breaks a `sort_order` tie in rowid order, so items added in
 * sequence come back in the order they were added, and a test that adds two
 * items and reads them straight back passes. The order only becomes wrong once
 * a row is rewritten — `createBatch` renumbers from 0, so an item added through
 * `addItem` afterwards ties with the batch's first row and is ordered by
 * rowid against a row that was inserted before it.
 *
 * The expectation written here is the one a user has: an item added to a list
 * appears after the items already in it. Asserted on the round trip, not on the
 * field, because the field is private to the implementation and the round trip
 * is what the UI renders.
 */
@Tag("fast")
class ChecklistOrderingTest {

    private fun repo(): ChecklistRepository {
        val auth = FakeAuthRepository(
            initialSession = Session.SignedIn(UserId("u1"), "test@test.com", "token", "refresh"),
        )
        val currentUser = FakeProfileAwareCurrentUser(authRepository = auth)
        return ChecklistRepositoryImpl(
            dao = FakeAppDatabase().checklistDao(),
            // FakeClock, not `Clock.System`: the repository only stamps createdAt/updatedAt
            // here, and a fixed instant keeps that out of the assertion's way.
            clock = FakeClock(),
            currentUser = currentUser,
        )
    }

    @Test
    fun `an item added after a batch is ordered last`() = runTest {
        val repo = repo()
        val taskId = "t-order"

        repo.createBatch(
            taskId,
            listOf(
                ChecklistItem(ChecklistItemId.generate(), taskId, "first", false, 0),
                ChecklistItem(ChecklistItemId.generate(), taskId, "second", false, 1),
            ),
        ).getOrThrow()

        // `createBatch` renumbers 0,1. An `addItem` also claims 0 — the tie the
        // DAO cannot resolve by sort_order, and which rowid then decides.
        repo.addItem(taskId, "third").getOrThrow()

        val titles = repo.watchByTask(taskId).first().map { it.title }
        assertEquals(
            listOf("first", "second", "third"),
            titles,
            "an item added to a list must come after the items already in it",
        )
    }

    @Test
    fun `items added one after another keep their order`() = runTest {
        val repo = repo()
        val taskId = "t-seq"

        repo.addItem(taskId, "a").getOrThrow()
        repo.addItem(taskId, "b").getOrThrow()
        repo.addItem(taskId, "c").getOrThrow()

        assertEquals(
            listOf("a", "b", "c"),
            repo.watchByTask(taskId).first().map { it.title },
        )
    }
}
