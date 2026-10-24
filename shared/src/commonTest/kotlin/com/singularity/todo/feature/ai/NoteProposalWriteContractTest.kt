@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.ai

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.ai.tools.CreateNoteInput
import com.singularity.todo.feature.ai.tools.CreateNoteOutput
import com.singularity.todo.feature.ai.tools.CreateNoteTool
import com.singularity.todo.feature.ai.tools.DeleteNoteInput
import com.singularity.todo.feature.ai.tools.DeleteNoteOutput
import com.singularity.todo.feature.ai.tools.DeleteNoteTool
import com.singularity.todo.feature.ai.tools.UpdateNoteInput
import com.singularity.todo.feature.ai.tools.UpdateNoteOutput
import com.singularity.todo.feature.ai.tools.UpdateNoteTool
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import com.singularity.todo.feature.proposals.domain.usecase.ProposalDispatch
import com.singularity.todo.feature.proposals.domain.usecase.ProposalPlanner
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeProposalRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeTimeTrackingRepository
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The write contract of the note tools that propose instead of writing.
 *
 * ## Why the refusal is the case that matters here
 *
 * A tool that is classified as proposal-gated but writes on its own would break the
 * promise the whole surface makes: *the user's data changes only after the user says
 * yes*. The write is the easy half to see; the refusal is the half that gets
 * forgotten — a tool that proposes and then writes anyway passes every test that
 * only looks at the confirmation path.
 *
 * So this test drives the whole round trip and asserts on the state of the user's
 * data after each step — not on the tool's own report of what it did.
 *
 * ## The three tools
 *
 * - [CreateNoteTool] — the note does not exist until the user says yes.
 * - [UpdateNoteTool] — the model names the fields to change, and the tool reads the
 *   note first so it can propose only what really differs.
 * - [DeleteNoteTool] — the note must still be there when the user says no.
 */
@Tag("fast")
class NoteProposalWriteContractTest {

    private val clock = FakeClock()
    private val user: UserId = TestUsers.DEFAULT

    private val proposals = FakeProposalRepository(clock)
    private val tasks = FakeTaskRepository()
    private val tags = FakeTagsRepository()
    private val checklist = FakeChecklistRepository()
    private val timeTracking = FakeTimeTrackingRepository(clock)
    private val notes = FakeNotesRepository()
    private val projectsFake = FakeProjectsRepository(FakeProfileAwareCurrentUser(user))
    private val deleteProject = DeleteProjectUseCase(projectsFake, tasks)

    private val currentUser = FakeProfileAwareCurrentUser(user)

    private val apply = ApplyProposalItemUseCase(
        proposals = proposals,
        tasks = tasks,
        notes = notes,
        projects = projectsFake,
        tags = tags,
        planner = ProposalPlanner(clock),
        dispatch = ProposalDispatch(tasks, tags, checklist, timeTracking, notes, projectsFake, deleteProject, clock),
    )

    // ── The catalogue and the behaviour must agree ──────────────────────────────

    @Test
    fun `the catalogue classifies the tools this test drives as proposal-gated`() {
        listOf("create_note", "update_note", "delete_note").forEach { name ->
            val entry = McpToolCatalog.catalog.single { it.name == name }
            assertEquals(
                WriteBehavior.PROPOSAL_WRITE,
                entry.writeBehavior,
                "$name is a proposal-gated tool",
            )
        }
    }

    // ── create_note: the note does not exist until the user says yes ───────────

    @Test
    fun `create_note proposes a note and creates nothing`() = runTest {
        val output = decodeCreate(
            CreateNoteTool(proposals, currentUser, clock).execute(CreateNoteInput(title = "Ideas")),
        )

        assertTrue(output.proposalCreated, "the tool reported a proposal: ${output.error}")
        assertTrue(output.proposalId.isNotBlank(), "the proposal is named, so the user can find it")
        assertEquals("Ideas", output.title, "the tool says what it proposed")
        assertTrue(notes.notes.isEmpty(), "nothing was created: ${notes.notes.keys}")

        val stored = proposals.watchProposal(ProposalId(output.proposalId)).first()
        assertNotNull(stored, "the proposal is in the store")
        assertEquals(1, stored.items.size, "the proposal holds one change")
        assertEquals(ProposalItemStatus.Pending, stored.items.first().status, "the change waits for the user")
    }

    @Test
    fun `confirming a create_note proposal creates the note`() = runTest {
        val output = decodeCreate(
            CreateNoteTool(proposals, currentUser, clock).execute(CreateNoteInput(title = "Ideas")),
        )
        val result = apply.confirm(singleItemId(output.proposalId), user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")

        val created = notes.notes.values.singleOrNull()
        assertNotNull(created, "the note exists")
        assertEquals("Ideas", created.title)
        assertEquals(user, created.userId)
    }

    @Test
    fun `declining a create_note proposal creates nothing`() = runTest {
        val output = decodeCreate(
            CreateNoteTool(proposals, currentUser, clock).execute(CreateNoteInput(title = "Ideas")),
        )
        val itemId = singleItemId(output.proposalId)
        val result = apply.reject(itemId, user, reason = "not needed")
        assertTrue(result.isSuccess, "the refusal succeeded: ${result.exceptionOrNull()?.message}")

        assertTrue(notes.notes.isEmpty(), "nothing was created: ${notes.notes.keys}")
        assertEquals(
            ProposalItemStatus.Rejected,
            proposals.getItem(itemId)?.status,
            "the change was refused, not left pending",
        )
    }

    // ── update_note: only what really differs is proposed ─────────────────────

    @Test
    fun `update_note proposes a change and writes nothing`() = runTest {
        val note = seedNote("Ideas")

        val output = decodeUpdate(
            UpdateNoteTool(proposals, notes, currentUser, clock)
                .execute(UpdateNoteInput(noteId = note.id.value, title = "Better ideas")),
        )

        assertTrue(output.proposalCreated, "the tool reported a proposal: ${output.error}")
        assertTrue(output.proposalId.isNotBlank(), "the proposal is named, so the user can find it")
        assertEquals(
            "Ideas",
            notes.notes[note.id.value]?.title,
            "the note was not touched by the call",
        )

        val stored = proposals.watchProposal(ProposalId(output.proposalId)).first()
        assertNotNull(stored, "the proposal is in the store")
        assertEquals(1, stored.items.size, "one field changed, so one change is proposed")
        assertEquals(ProposalItemStatus.Pending, stored.items.first().status, "the change waits for the user")
    }

    @Test
    fun `update_note refuses a note that does not exist and proposes nothing`() = runTest {
        val output = decodeUpdate(
            UpdateNoteTool(proposals, notes, currentUser, clock)
                .execute(UpdateNoteInput(noteId = "no-such-note", title = "Better ideas")),
        )

        assertFalse(output.proposalCreated, "no proposal was made: ${output.error}")
        assertNotNull(output.error, "the model is told why nothing was proposed")
        assertTrue(output.proposalId.isBlank(), "there is no proposal to find")
        assertTrue(notes.notes.isEmpty(), "nothing was written: ${notes.notes.keys}")
    }

    @Test
    fun `confirming an update_note proposal writes the change`() = runTest {
        val note = seedNote("Ideas")

        val output = decodeUpdate(
            UpdateNoteTool(proposals, notes, currentUser, clock)
                .execute(UpdateNoteInput(noteId = note.id.value, title = "Better ideas")),
        )
        val result = apply.confirm(singleItemId(output.proposalId), user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")

        assertEquals(
            "Better ideas",
            notes.notes[note.id.value]?.title,
            "the confirmed change is in the user's note",
        )
    }

    @Test
    fun `declining an update_note proposal leaves the note unchanged`() = runTest {
        val note = seedNote("Ideas")

        val output = decodeUpdate(
            UpdateNoteTool(proposals, notes, currentUser, clock)
                .execute(UpdateNoteInput(noteId = note.id.value, title = "Better ideas")),
        )
        val itemId = singleItemId(output.proposalId)
        val result = apply.reject(itemId, user, reason = "not now")
        assertTrue(result.isSuccess, "the refusal succeeded: ${result.exceptionOrNull()?.message}")

        assertEquals(
            "Ideas",
            notes.notes[note.id.value]?.title,
            "the note is exactly as it was",
        )
        assertEquals(
            ProposalItemStatus.Rejected,
            proposals.getItem(itemId)?.status,
            "the change was refused, not left pending",
        )
    }

    // ── delete_note: the note must survive the refusal ─────────────────────────

    @Test
    fun `delete_note proposes the deletion and deletes nothing`() = runTest {
        val note = seedNote("Ideas")

        val output = decodeDelete(
            DeleteNoteTool(proposals, currentUser, clock)
                .execute(DeleteNoteInput(noteId = note.id.value, reason = "no longer needed")),
        )

        assertTrue(output.proposalCreated, "the tool reported a proposal: ${output.error}")
        assertTrue(output.proposalId.isNotBlank(), "the proposal is named, so the user can find it")
        val stillThere = assertNotNull(notes.notes[note.id.value], "the note is still there")
        assertNull(stillThere.archivedAt, "the note is not archived")

        val stored = proposals.watchProposal(ProposalId(output.proposalId)).first()
        assertNotNull(stored, "the proposal is in the store")
        assertEquals(1, stored.items.size, "the proposal holds one change")
        assertEquals(ProposalItemStatus.Pending, stored.items.first().status, "the change waits for the user")
    }

    @Test
    fun `confirming a delete_note proposal archives the note`() = runTest {
        val note = seedNote("Ideas")

        val output = decodeDelete(
            DeleteNoteTool(proposals, currentUser, clock)
                .execute(DeleteNoteInput(noteId = note.id.value, reason = "no longer needed")),
        )
        val result = apply.confirm(singleItemId(output.proposalId), user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")

        val archived = assertNotNull(notes.notes[note.id.value], "the note row is still readable")
        assertNotNull(archived.archivedAt, "the confirmed deletion archived the note")
        assertTrue(liveNotes().isEmpty(), "the note left the user's list: ${liveNotes()}")
    }

    @Test
    fun `declining a delete_note proposal leaves the note in place`() = runTest {
        val note = seedNote("Ideas")

        val output = decodeDelete(
            DeleteNoteTool(proposals, currentUser, clock)
                .execute(DeleteNoteInput(noteId = note.id.value, reason = "no longer needed")),
        )
        val itemId = singleItemId(output.proposalId)
        val result = apply.reject(itemId, user, reason = "keep it")
        assertTrue(result.isSuccess, "the refusal succeeded: ${result.exceptionOrNull()?.message}")

        val survivor = assertNotNull(notes.notes[note.id.value], "the note survived the refusal")
        assertNull(survivor.archivedAt, "the note was not archived")
        assertEquals(1, liveNotes().size, "the note is still in the user's list")
        assertEquals(
            ProposalItemStatus.Rejected,
            proposals.getItem(itemId)?.status,
            "the change was refused, not left pending",
        )
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────

    private fun seedNote(title: String): Note {
        val note = Note(
            id = NoteId.generate(),
            userId = user,
            title = title,
            bodyMarkdown = null,
            bodyHtml = "<p>first</p>",
            createdAt = clock.now(),
            updatedAt = clock.now(),
        )
        notes.seed(note)
        return note
    }

    /** The notes the user can see — the list an un-archived note belongs to. */
    private suspend fun liveNotes(): List<Note> = notes.observeAll().first()

    private fun decodeCreate(raw: String): CreateNoteOutput = Json.decodeFromString(CreateNoteOutput.serializer(), raw)

    private fun decodeUpdate(raw: String): UpdateNoteOutput = Json.decodeFromString(UpdateNoteOutput.serializer(), raw)

    private fun decodeDelete(raw: String): DeleteNoteOutput = Json.decodeFromString(DeleteNoteOutput.serializer(), raw)

    private suspend fun singleItemId(proposalId: String) =
        requireNotNull(proposals.watchProposal(ProposalId(proposalId)).first()?.items?.singleOrNull()).id
}
