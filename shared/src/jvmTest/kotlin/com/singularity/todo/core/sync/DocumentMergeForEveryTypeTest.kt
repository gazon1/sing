package com.singularity.todo.core.sync

import com.singularity.todo.core.ids.TimeEntryId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteColor
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.domain.model.TagGroup
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDocument
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.timetracking.domain.TimeEntry
import com.singularity.todo.feature.timetracking.domain.TimeEntryKind
import com.singularity.todo.feature.timetracking.domain.TimeEntrySource
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeTagGroupRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeTimeTrackingRepository
import com.singularity.todo.test.fakes.TestUsers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag

/**
 * The row keeps what the document does not say — for every type that is synced.
 *
 * ## The defect this is for
 *
 * The rule was written for the task: a document that carries no key for a field
 * is not a claim that the field is empty, and an empty field is a deletion. The
 * task's path read the document against the row that was already here. The other
 * five types decoded the document on its own and wrote it whole, so a document
 * that arrived without a key wrote the *default* over the row: a note lost its
 * body, a tag lost its group, a time entry lost its note — silently, because
 * the row was written and the pull reported success.
 *
 * The wire is the reason the reading matters. A document carries the fields the
 * sending device knew about, and the allow-list says what a device sends, and
 * it grew.
 *
 * ## Why every type is in one file
 *
 * The rule is one rule, and a file per type would be five copies of it with
 * five chances to drift. The types differ in what they are — a note, a tag, a
 * group, an entry — and not in how the document meets the row.
 *
 * The documents here are the documents a device sends: a whole row, with the
 * keys an older device did not know about left out.
 *
 * ## What these tests are and are not
 *
 * The tests that carry a missing key are the defect tests: without the merge
 * they fail, because the default is written over the row.
 *
 * The test about the row of another profile is a control for the *boundary*,
 * not for the merge: it holds a row of this profile under the id the foreign
 * document names, and it fails when the check that says "this document is of
 * another profile" is taken out. It passes with the merge reverted, because
 * the merge is not what keeps a profile's data out of another profile's row.
 */
@Tag("fast")
class DocumentMergeForEveryTypeTest {

    private val created = Instant.fromEpochMilliseconds(1_000)

    // ── the note ───────────────────────────────────────────────────────────

    private fun note(
        id: String = "n1",
        userId: UserId = TestUsers.DEFAULT,
        body: String? = "the body",
    ) = Note(
        id = NoteId(id),
        userId = userId,
        title = "Ideas",
        bodyMarkdown = body,
        color = NoteColor.Red,
        createdAt = created,
        updatedAt = created,
    )

    @Test
    fun `a note document that carries no body leaves the body of the note alone`() = runTest {
        val repo = FakeNotesRepository()
        repo.add(note())

        fakeSyncDocumentWriter(notes = repo).upsert(
            DocType.Note,
            JsonObject(note().toJson() - "bodyMarkdown"),
        )

        assertEquals(
            "the body",
            assertNotNull(repo.get(NoteId("n1"))).bodyMarkdown,
            "the body of the note is still the body of the note",
        )
    }

    @Test
    fun `a note document that speaks about the body wins`() = runTest {
        val repo = FakeNotesRepository()
        repo.add(note())

        fakeSyncDocumentWriter(notes = repo).upsert(
            DocType.Note,
            JsonObject(note(body = "another body").toJson()),
        )

        assertEquals(
            "another body",
            assertNotNull(repo.get(NoteId("n1"))).bodyMarkdown,
            "the document says what it carries",
        )
    }

    @Test
    fun `a note document that carries no color leaves the color of the note alone`() = runTest {
        val repo = FakeNotesRepository()
        repo.add(note())

        fakeSyncDocumentWriter(notes = repo).upsert(
            DocType.Note,
            JsonObject(note().toJson() - "color"),
        )

        assertEquals(
            NoteColor.Red,
            assertNotNull(repo.get(NoteId("n1"))).color,
            "the color of the note is still the color of the note",
        )
    }

    // ── the tag ────────────────────────────────────────────────────────────

    private fun tag(
        id: String = "tag-1",
        userId: UserId = TestUsers.DEFAULT,
        group: TagGroupId? = TagGroupId("g1"),
    ) = com.singularity.todo.feature.tags.Tag(
        id = TagId(id),
        name = "urgent",
        color = 0xFFFF0000.toInt(),
        createdAt = created,
        updatedAt = created,
        groupId = group,
        userId = userId,
    )

    @Test
    fun `a tag document that carries no group leaves the group of the tag alone`() = runTest {
        val repo = FakeTagsRepository()
        repo.add(tag())

        fakeSyncDocumentWriter(tags = repo).upsert(
            DocType.Tag,
            JsonObject(tag().toJson() - "groupId"),
        )

        assertEquals(
            TagGroupId("g1"),
            assertNotNull(repo.get(TagId("tag-1"))).groupId,
            "the group of the tag is still the group of the tag",
        )
    }

    @Test
    fun `a tag document that speaks about the group wins`() = runTest {
        val repo = FakeTagsRepository()
        repo.add(tag())

        fakeSyncDocumentWriter(tags = repo).upsert(
            DocType.Tag,
            JsonObject(tag(group = TagGroupId("g2")).toJson()),
        )

        assertEquals(
            TagGroupId("g2"),
            assertNotNull(repo.get(TagId("tag-1"))).groupId,
            "the document says what it carries",
        )
    }

    // ── the tag group ──────────────────────────────────────────────────────

    private fun group(
        id: String = "g1",
        userId: UserId = TestUsers.DEFAULT,
        name: String = "work",
    ) = TagGroup(
        id = TagGroupId(id),
        name = name,
        color = 0xFF00FF00.toInt(),
        createdAt = created,
        updatedAt = created,
        userId = userId,
    )

    @Test
    fun `a tag group document that carries no name is read against the row that is here`() = runTest {
        // The name is a field the model cannot do without, so a document
        // without it cannot be decoded on its own: the pull drops it and the
        // row keeps the name it had — by accident rather than by rule. Read
        // against the row that is here, the document is a partial write: the
        // name of the row stays, and the rest of the document is applied.
        val repo = FakeTagGroupRepository()
        repo.seed(group())

        fakeSyncDocumentWriter(tagGroups = repo).upsert(
            DocType.TagGroup,
            JsonObject(mapOf("id" to JsonPrimitive("g1"))),
        )

        assertEquals(
            "work",
            assertNotNull(repo.get(TagGroupId("g1"))).name,
            "the name of the group is still the name of the group",
        )
    }

    // ── the time entry ─────────────────────────────────────────────────────

    private fun entry(
        id: String = "e1",
        userId: UserId = TestUsers.DEFAULT,
        note: String? = "focus",
    ) = TimeEntry(
        id = TimeEntryId(id),
        taskId = TaskId("t1"),
        userId = userId,
        startedAt = created,
        endedAt = null,
        kind = TimeEntryKind.Work,
        source = TimeEntrySource.Manual,
        note = note,
        createdAt = created,
        updatedAt = created,
    )

    @Test
    fun `a time entry document that carries no note leaves the note of the entry alone`() = runTest {
        val repo = FakeTimeTrackingRepository(FakeClock())
        repo.upsert(entry())

        fakeSyncDocumentWriter(timeTracking = repo).upsert(
            DocType.TimeEntry,
            JsonObject(entry().toJson() - "note"),
        )

        assertEquals(
            "focus",
            assertNotNull(repo.get(TimeEntryId("e1"), TestUsers.DEFAULT)).note,
            "the note of the entry is still the note of the entry",
        )
    }

    @Test
    fun `a time entry document that speaks about the note wins`() = runTest {
        val repo = FakeTimeTrackingRepository(FakeClock())
        repo.upsert(entry())

        fakeSyncDocumentWriter(timeTracking = repo).upsert(
            DocType.TimeEntry,
            JsonObject(entry(note = "another note").toJson()),
        )

        assertEquals(
            "another note",
            assertNotNull(repo.get(TimeEntryId("e1"), TestUsers.DEFAULT)).note,
            "the document says what it carries",
        )
    }

    // ── the row of another profile ─────────────────────────────────────────

    @Test
    fun `a note document of another profile is not filled from this profile's row`() = runTest {
        // A document of another profile can name a row this device already
        // holds: the row is keyed by the id alone, so the foreign document
        // lands on this profile's row. The foreign document carries no body at
        // all, and the row says "the body": filling the foreign document from
        // this profile's row would write this profile's data into a document
        // that says the note has no body.
        val repo = FakeNotesRepository()
        repo.add(note(id = "n1", body = "the body"))

        fakeSyncDocumentWriter(notes = repo).upsert(
            DocType.Note,
            JsonObject(note(id = "n1", userId = UserId("bob")).toJson() - "bodyMarkdown"),
        )

        assertNull(
            repo.notes["n1"]?.bodyMarkdown,
            "the row of another profile is not filled from this profile's row",
        )
    }

    // ── the row this device has never seen ─────────────────────────────────

    @Test
    fun `a note document about a note this device has never seen is the note`() = runTest {
        val repo = FakeNotesRepository()
        val arriving = note(id = "n9", body = null)

        fakeSyncDocumentWriter(notes = repo).upsert(DocType.Note, arriving.toJson())

        assertEquals(
            "Ideas",
            assertNotNull(repo.get(NoteId("n9"))).title,
            "the document is the note",
        )
    }

    // ── the parts that live outside the row ────────────────────────────────

    @Test
    fun `the tags of the row are not a claim about the tags`() = runTest {
        // The tags live in a cross-reference table, and the row does not hold
        // them: a row read through `get()` holds no tags at all, and that
        // emptiness is a reading artefact, not a claim. The merge leaves the
        // tags to the document — so the merged task claims no tags, and the
        // row keeps the tags it had.
        val repo = RecordingTaskRepository()
        val task = Task(
            id = TaskId("t1"),
            title = "Pay the rent",
            userId = TestUsers.DEFAULT,
            tags = listOf(TagId("tag-1")),
            createdAt = created,
            updatedAt = created,
        )
        repo.add(task)

        fakeSyncDocumentWriter(tasks = repo).upsert(
            DocType.Task,
            JsonObject(task.toJson() - "tags"),
        )

        val document = repo.documents.single()
        assertEquals(emptyList(), document.task.tags, "the row makes no claim about the tags")
        assertNull(document.tags, "the document says nothing about the tags")
        assertEquals(
            listOf(TagId("tag-1")),
            repo.tasks.value.getValue("t1").tags,
            "the tags of the row are still there",
        )
    }

    /** A task repository that keeps the documents it was given. */
    private class RecordingTaskRepository : FakeTaskRepository() {
        val documents = mutableListOf<TaskDocument>()

        override suspend fun upsert(document: TaskDocument): Task {
            documents += document
            return super.upsert(document)
        }
    }
}
