@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.agenda

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.AgendaViewDao
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.agenda.data.SavedAgendaViewsRepositoryImpl
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [SavedAgendaViewsRepositoryImpl] via [FakeAppDatabase].
 *
 * Verifies: observeAll, observe, upsert, delete, and the StableJson sections round-trip.
 */
class SavedAgendaViewsRepositoryImplTest {

    private fun repo(): SavedAgendaViewsRepository = repoWithDao().first

    /**
     * The repository under test together with its DAO, so a test can assert which
     * namespace a row physically landed in. The repository API is scoped to the
     * *current* user, so it cannot express "the row is in someone else's
     * namespace" — the whole point of the duplicateForProfile contract.
     */
    private fun repoWithDao(): Pair<SavedAgendaViewsRepository, AgendaViewDao> {
        val db = FakeAppDatabase()
        val auth = FakeAuthRepository(
            initialSession = Session.SignedIn(UserId("u1"), "test@test.com", "token", "refresh"),
        )
        val currentUser = FakeProfileAwareCurrentUser(authRepository = auth)
        return SavedAgendaViewsRepositoryImpl(db.agendaViewDao(), currentUser) to db.agendaViewDao()
    }

    private fun makeView(
        id: String = "v1",
        userId: UserId = UserId("user_1"),
        name: String = "My Agenda",
        sectionsJson: String = """{"title":"Test","sections":[]}""",
    ): SavedAgendaView = SavedAgendaView(
        id = SavedAgendaViewId.fromString(id),
        userId = userId,
        name = name,
        sectionsJson = sectionsJson,
        createdAt = kotlin.time.Instant.fromEpochMilliseconds(1_000_000_000L),
        updatedAt = kotlin.time.Instant.fromEpochMilliseconds(1_000_000_000L),
    )

    @Test
    fun observeAll_returnsEmptyForUnknownUser() = runTest {
        val r = repo()
        val result = r.observeAll()
            .first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun watchAll_returnsViewsForUser() = runTest {
        val r = repo()
        r.upsert(makeView(id = "v1", userId = UserId("u1"), name = "Alpha"))
        r.upsert(makeView(id = "v2", userId = UserId("u1"), name = "Beta"))
        r.upsert(makeView(id = "v3", userId = UserId("u2"), name = "Gamma")) // other user — not returned by scoped query

        val result = r.observeAll()
            .first()
        assertEquals(2, result.size)
        assertEquals("Alpha", result[0].name)
        assertEquals("Beta", result[1].name)
    }

    @Test
    fun watchAll_sortsByName() = runTest {
        val r = repo()
        r.upsert(makeView(id = "v1", userId = UserId("u1"), name = "Zeta"))
        r.upsert(makeView(id = "v2", userId = UserId("u1"), name = "Alpha"))
        r.upsert(makeView(id = "v3", userId = UserId("u1"), name = "Beta"))

        val result = r.observeAll()
            .first()
        assertEquals(listOf("Alpha", "Beta", "Zeta"), result.map { it.name })
    }

    @Test
    fun watchById_returnsView() = runTest {
        val r = repo()
        val created = r.upsert(makeView(id = "v1", userId = UserId("u1")))
            .getOrThrow()

        val result = r.observe(SavedAgendaViewId.fromString("v1"))
            .first()
        assertNotNull(result)
        assertEquals(created.id, result.id)
        assertEquals("My Agenda", result.name)
    }

    @Test
    fun watchById_returnsNullForUnknownId() = runTest {
        val r = repo()
        val result = r.observe(SavedAgendaViewId.fromString("unknown"))
            .first()
        assertNull(result)
    }

    @Test
    fun upsert_createsView() = runTest {
        val r = repo()
        val view = makeView(id = "v1", userId = UserId("u1"), name = "New View")

        val result = r.upsert(view)
            .getOrThrow()
        assertEquals("v1", result.id.raw)
        assertEquals("New View", result.name)
    }

    @Test
    fun upsert_updatesExistingView() = runTest {
        val r = repo()
        r.upsert(makeView(id = "v1", userId = UserId("u1"), name = "Original"))

        val updated = r.upsert(makeView(id = "v1", userId = UserId("u1"), name = "Updated"))
            .getOrThrow()
        assertEquals("Updated", updated.name)

        val all = r.observeAll()
            .first()
        assertEquals(1, all.size)
    }

    @Test
    fun delete_removesView() = runTest {
        val r = repo()
        r.upsert(makeView(id = "v1", userId = UserId("u1")))

        r.delete(SavedAgendaViewId.fromString("v1"))

        val result = r.observeAll()
            .first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun delete_isIdempotent() = runTest {
        val r = repo()
        val result = r.delete(SavedAgendaViewId.fromString("unknown"))
        assertTrue(result.isSuccess)
    }

    @Test
    fun sectionsJson_preservesFullAgendaDefinition() = runTest {
        val r = repo()
        val sectionsJson = """
            {"title":"Upcoming","sections":[
                {"name":"Today","order":0,"selector":{"_type":"DateBucket","bucket":"Today"}},
                {"name":"This Week","order":1,"selector":{"_type":"DateBucket","bucket":"ThisWeek"}}
            ]}
        """.trimIndent()

        val view = makeView(id = "v1", userId = UserId("u1"), sectionsJson = sectionsJson)
        r.upsert(view)
            .getOrThrow()

        val restored = r.observe(SavedAgendaViewId.fromString("v1"))
            .first()
        assertNotNull(restored)
        assertTrue(restored.sectionsJson.contains("Upcoming"))
        assertTrue(restored.sectionsJson.contains("ThisWeek"))
    }

    // ─── duplicateForProfile — the one sanctioned cross-profile write ─────────

    /**
     * Regression guard for the copy-to-profile bug found by the MR-5 Maestro run.
     *
     * `duplicateForProfile` used to route the copy through [SavedAgendaViewsRepositoryImpl.upsert],
     * which re-stamps `userId` with the *current* scoped user. The copy therefore landed
     * back in the source profile's namespace: the target profile stayed empty and the
     * source profile gained a duplicate card. The fix writes through the DAO directly.
     */
    @Test
    fun duplicateForProfile_writesTheCopyIntoTheTargetProfileNamespace() = runTest {
        val (r, dao) = repoWithDao()
        val original = r.upsert(makeView(id = "v1", userId = UserId("u1"), name = "Shared"))
            .getOrThrow()

        val targetScoped = "work/u1"
        val copy = r.duplicateForProfile(original, targetScoped)
            .getOrThrow()

        // A fresh id, not an in-place overwrite of the source row.
        assertTrue(copy.id != original.id, "the copy must get its own id")

        val inTarget = dao.listAllForUser(targetScoped)
        assertEquals(1, inTarget.size, "the copy must land in the target profile's namespace")
        assertEquals("Shared", inTarget.single().name)
        assertEquals(targetScoped, inTarget.single().userId)
        assertEquals(copy.id.raw, inTarget.single().id)
    }

    /**
     * The other half of the same bug: routing through `upsert` is what put the copy
     * back under the source user. Assert the source namespace is left exactly as it was.
     */
    @Test
    fun duplicateForProfile_doesNotRestampTheCopyIntoTheSourceNamespace() = runTest {
        val (r, dao) = repoWithDao()
        val original = r.upsert(makeView(id = "v1", userId = UserId("u1"), name = "Shared"))
            .getOrThrow()

        r.duplicateForProfile(original, "work/u1")
            .getOrThrow()

        val inSource = dao.listAllForUser("u1")
        assertEquals(1, inSource.size, "the source profile must still hold only the original")
        assertEquals(original.id.raw, inSource.single().id, "the original row must be untouched")
        assertEquals("Shared", inSource.single().name)

        // And the repository's own scoped read still sees one view, not two.
        assertEquals(1, r.observeAll().first().size)
    }
}
