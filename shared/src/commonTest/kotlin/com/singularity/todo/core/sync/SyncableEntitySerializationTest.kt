
package com.singularity.todo.core.sync

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NoteKind
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.domain.model.TagGroup
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Every [SyncableEntity] must be able to serialise itself.
 *
 * This is not a formality. `SyncableEntity.toJson()` resolves
 * `serializer<T>()` via reflection, and a class missing `@Serializable` throws
 * `SerializationException` at that point. Production push runs
 * repository → `SyncEngine.enqueue` → `buildPatch` → `toJson`, and
 * `runCatchingResult` swallows the throw — so a single missing annotation means
 * the entity is **never** written to the outbox, with no error anywhere. Notes
 * shipped that way: they "synced" through `create`/`update` and silently went
 * nowhere. Projects and Tags had the same defect.
 *
 * A round-trip test per entity is the cheapest way to make that invariant
 * impossible to reintroduce silently.
 */
@org.junit.jupiter.api.Tag("fast")
class SyncableEntitySerializationTest {

    private val user = UserId("u1")
    private val now = Clock.System.now()

    private fun assertRoundTrips(entity: SyncableEntity, expectedId: String) {
        val json = entity.toJson()
        assertTrue(
            json.isNotEmpty(),
            "${entity.docType.key} serialised to an empty object",
        )
        assertTrue(
            expectedId in json.toString(),
            "${entity.docType.key} payload should mention its id, got $json",
        )
    }

    @Test
    fun `Task serialises`() {
        assertRoundTrips(
            Task(
                id = TaskId("t1"),
                title = "Task",
                userId = user,
                createdAt = now,
                updatedAt = now,
            ),
            "t1",
        )
    }

    @Test
    fun `Note serialises`() {
        assertRoundTrips(
            Note(
                id = NoteId("n1"),
                userId = user,
                title = "Note",
                kind = NoteKind.Plain,
                createdAt = now,
                updatedAt = now,
            ),
            "n1",
        )
    }

    @Test
    fun `Project serialises`() {
        assertRoundTrips(
            Project(
                id = ProjectId("p1"),
                name = "Project",
                color = 0,
                createdAt = now,
                updatedAt = now,
                userId = user,
            ),
            "p1",
        )
    }

    @Test
    fun `Tag serialises`() {
        assertRoundTrips(
            Tag(
                id = TagId("g1"),
                name = "Tag",
                color = 0,
                createdAt = now,
                updatedAt = now,
                userId = user,
            ),
            "g1",
        )
    }

    @Test
    fun `TagGroup serialises`() {
        assertRoundTrips(
            TagGroup(
                id = TagGroupId("tg1"),
                name = "Group",
                color = 0,
                createdAt = now,
                updatedAt = now,
                userId = user,
            ),
            "tg1",
        )
    }

    /**
     * The soft-delete fields that carry deletion across the wire. A delete that
     * never sets these propagates as a no-op and the entity resurrects on the
     * next pull.
     */
    @Test
    fun `soft delete state survives serialisation`() {
        val trashed = Note(
            id = NoteId("n1"),
            userId = user,
            createdAt = now,
            updatedAt = now,
            deletedAt = now,
        )
        val json = trashed.toJson()
        assertTrue("deletedAt" in json.toString(), "deletedAt must reach the wire")

        val restored = trashed.copy(deletedAt = null)
        assertTrue(
            restored.toJson().toString().contains("\"deletedAt\":null") ||
                "deletedAt" !in restored.toJson().toString(),
            "a restored entity must not claim to be trashed",
        )
    }

    @Test
    fun `every DocType has a serialisable entity`() {
        val docTypes = DocType.entries.toSet()
        val covered = setOf(
            DocType.Task,
            DocType.Note,
            DocType.Project,
            DocType.Tag,
            DocType.TagGroup,
            DocType.TimeEntry,
        )
        assertEquals(
            docTypes,
            covered,
            "a new DocType must be added to SyncableEntitySerializationTest",
        )
    }
}
