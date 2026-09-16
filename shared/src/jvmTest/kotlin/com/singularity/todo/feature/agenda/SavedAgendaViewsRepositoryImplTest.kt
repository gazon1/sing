package com.singularity.todo.feature.agenda

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.agenda.data.RoomSavedAgendaViewsRepository
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.test.fakes.FakeAppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Tests for [RoomSavedAgendaViewsRepository] via [FakeAppDatabase].
 *
 * Verifies: watchAll, watchById, upsert, delete, and the StableJson sections round-trip.
 */
class SavedAgendaViewsRepositoryImplTest {

    private fun repo(): SavedAgendaViewsRepository {
        val db = FakeAppDatabase()
        return RoomSavedAgendaViewsRepository(db.agendaViewDao())
    }

    private fun makeView(
        id: String = "v1",
        userId: String = "user_1",
        name: String = "My Agenda",
        sectionsJson: String = """{"title":"Test","sections":[]}""",
    ): SavedAgendaView = SavedAgendaView(
        id = SavedAgendaViewId.fromString(id),
        userId = userId,
        name = name,
        sectionsJson = sectionsJson,
        createdAt = Instant.fromEpochMilliseconds(1_000_000_000L),
        updatedAt = Instant.fromEpochMilliseconds(1_000_000_000L),
    )

    @Test
    fun watchAll_returnsEmptyForUnknownUser() = runTest {
        val r = repo()
        val result = r.watchAll("unknown-user").first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun watchAll_returnsViewsForUser() = runTest {
        val r = repo()
        r.upsert(makeView(id = "v1", userId = "u1", name = "Alpha"))
        r.upsert(makeView(id = "v2", userId = "u1", name = "Beta"))
        r.upsert(makeView(id = "v3", userId = "u2", name = "Gamma")) // other user

        val result = r.watchAll("u1").first()
        assertEquals(2, result.size)
        assertEquals("Alpha", result[0].name)
        assertEquals("Beta", result[1].name)
    }

    @Test
    fun watchAll_sortsByName() = runTest {
        val r = repo()
        r.upsert(makeView(id = "v1", userId = "u1", name = "Zeta"))
        r.upsert(makeView(id = "v2", userId = "u1", name = "Alpha"))
        r.upsert(makeView(id = "v3", userId = "u1", name = "Beta"))

        val result = r.watchAll("u1").first()
        assertEquals(listOf("Alpha", "Beta", "Zeta"), result.map { it.name })
    }

    @Test
    fun watchById_returnsView() = runTest {
        val r = repo()
        val created = r.upsert(makeView(id = "v1", userId = "u1")).getOrThrow()

        val result = r.watchById(SavedAgendaViewId.fromString("v1"), "u1").first()
        assertNotNull(result)
        assertEquals(created.id, result.id)
        assertEquals("My Agenda", result.name)
    }

    @Test
    fun watchById_returnsNullForUnknownId() = runTest {
        val r = repo()
        val result = r.watchById(SavedAgendaViewId.fromString("unknown"), "u1").first()
        assertNull(result)
    }

    @Test
    fun watchById_returnsNullForWrongUser() = runTest {
        val r = repo()
        r.upsert(makeView(id = "v1", userId = "u1"))

        val result = r.watchById(SavedAgendaViewId.fromString("v1"), "u2").first()
        assertNull(result)
    }

    @Test
    fun upsert_createsView() = runTest {
        val r = repo()
        val view = makeView(id = "v1", userId = "u1", name = "New View")

        val result = r.upsert(view).getOrThrow()
        assertEquals("v1", result.id.raw)
        assertEquals("New View", result.name)
    }

    @Test
    fun upsert_updatesExistingView() = runTest {
        val r = repo()
        r.upsert(makeView(id = "v1", userId = "u1", name = "Original"))

        val updated = r.upsert(makeView(id = "v1", userId = "u1", name = "Updated")).getOrThrow()
        assertEquals("Updated", updated.name)

        val all = r.watchAll("u1").first()
        assertEquals(1, all.size)
    }

    @Test
    fun delete_removesView() = runTest {
        val r = repo()
        r.upsert(makeView(id = "v1", userId = "u1"))

        r.delete(SavedAgendaViewId.fromString("v1"), "u1")

        val result = r.watchAll("u1").first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun delete_isIdempotent() = runTest {
        val r = repo()
        val result = r.delete(SavedAgendaViewId.fromString("unknown"), "u1")
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

        val view = makeView(id = "v1", userId = "u1", sectionsJson = sectionsJson)
        r.upsert(view).getOrThrow()

        val restored = r.watchById(SavedAgendaViewId.fromString("v1"), "u1").first()
        assertNotNull(restored)
        assertTrue(restored.sectionsJson.contains("Upcoming"))
        assertTrue(restored.sectionsJson.contains("ThisWeek"))
    }
}
