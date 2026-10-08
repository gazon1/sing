@file:Suppress("NoDirectClockSystem")

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.notes.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.domain.editor.NoteAiController
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import com.singularity.todo.feature.proposals.domain.usecase.ProposalDispatch
import com.singularity.todo.feature.proposals.domain.usecase.ProposalPlanner
import com.singularity.todo.feature.search.domain.port.InternalLinkRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeIdGenerator
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeProposalRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeTimeTrackingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * A save that could not write the outgoing links is not reported as a save (REQ-DRAFT-001).
 *
 * ## What this is a control for
 *
 * `NoteEditor.persist` ends by writing the note's outgoing links, under a comment
 * naming that line as *the write path for the backlinks feature*: without it,
 * `outgoing_links` is never written and every `[[note://…]]` / `[[task://…]]` link in
 * the note is dead. Two lines above, `createWithContent` and `updateContent` are
 * unwrapped with `.getOrThrow()`. The link write was not, and its `Result` went
 * nowhere.
 *
 * So a save could report itself successful, the user saw their note saved, and the
 * backlinks feature silently did nothing — with the comment above the line asserting
 * the opposite of what the line guarantees.
 *
 * ## What this is not
 *
 * Not a sync divergence. `SyncedWriteEnqueuesTest` records outgoing links as not
 * synced: no `DocType` describes them and the server has no table. The loss is local
 * and total for that column, not a divergence between devices. The audit that found
 * this rated it a sync defect; it is a plain dropped write, and fixing it costs one
 * unwrap rather than a reconciliation design.
 *
 * ## Why the assertion is on the editor's own error
 *
 * `persist` is `protected`, and `save()` is deliberately not `open` — the house
 * already decided that the failure handling belongs in the base body rather than in a
 * subclass. So the control asserts what the user sees: an error on the state, and the
 * draft still dirty. A control that reached into `persist` directly would be testing
 * the one path the design forbids overriding.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class NoteEditorOutgoingLinksFailureTest {

    private val userId = UserId("test-user")

    private val note = Note(
        id = NoteId("note-1"),
        userId = userId,
        title = "Test Note",
        bodyHtml = "<p>See [[note://note-2]]</p>",
        wordCount = 2,
        charCount = 11,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private val emptyLinkRepo = object : InternalLinkRepository {
        override suspend fun searchNotes(query: String): List<Note> = emptyList()
        override suspend fun searchTasks(query: String): List<Task> = emptyList()
        override suspend fun getBacklinkNotes(noteId: String): List<Note> = emptyList()
        override suspend fun getBacklinkTasks(taskId: String): List<Task> = emptyList()
        override suspend fun getNotesLinkingToTask(taskId: String): List<Note> = emptyList()
    }

    private fun createVm(notesRepo: FakeNotesRepository, scope: CoroutineScope): NoteEditor {
        val clock = FakeClock()
        val currentUser = FakeProfileAwareCurrentUser()
        val proposals = FakeProposalRepository(clock)
        val tasks = FakeTaskRepository()
        val tags = FakeTagsRepository()
        val checklist = FakeChecklistRepository()
        val timeTracking = FakeTimeTrackingRepository(clock)
        val notes = notesRepo
        val projects: ProjectsRepository = FakeProjectsRepository(currentUser)
        val deleteProject = DeleteProjectUseCase(projects, tasks)
        val applyProposal = ApplyProposalItemUseCase(
            proposals = proposals,
            tasks = tasks,
            notes = notes,
            tags = tags,
            planner = ProposalPlanner(clock),
            dispatch = ProposalDispatch(tasks, tags, checklist, timeTracking, notes, deleteProject, clock),
        )
        return NoteEditor(
            repo = notes,
            linkRepo = emptyLinkRepo,
            idGen = FakeIdGenerator("note"),
            ai = NoteAiController(improveNote = null),
            proposals = proposals,
            applyProposal = applyProposal,
            log = Logger.withTag("NoteEditorOutgoingLinksFailureTest"),
            currentUser = currentUser,
            clock = clock,
            scope = AutoCloseableCoroutineScope(scope.coroutineContext),
        )
    }

    /**
     * Counts the link writes instead of only refusing them.
     *
     * The override alone cannot distinguish "the save failed and nobody said so" from
     * "the save never reached the link write" — both leave the caller with no error. The
     * count separates them, and it is why the probe existed at all.
     */
    private class CountingNotesRepository : FakeNotesRepository() {
        var linkWrites: Int = 0
        var rejectLinkWrites: Boolean = false

        override suspend fun setOutgoingLinks(id: NoteId, links: List<String>): Result<Unit> {
            linkWrites++
            return if (rejectLinkWrites) {
                Result.failure(IllegalStateException("read-only column"))
            } else {
                super.setOutgoingLinks(id, links)
            }
        }
    }

    @Test
    fun `a save that could not write the outgoing links is not reported as a save`() = runTest {
        val notesRepo = CountingNotesRepository()
        notesRepo.seed(note)
        // A column that will not take the write — a locked row, a read-only mount,
        // a migration that has not run yet. All of them look the same to the caller.
        notesRepo.rejectLinkWrites = true
        val vm = createVm(notesRepo, backgroundScope)

        vm.openEditor(note.id.value)
        advanceTimeBy(1_000)
        runCurrent()
        vm.onIntent(NotesEditorIntent.EditBody("<p>See [[note://note-2]] and [[task://task-9]]</p>"))
        runCurrent()

        vm.onIntent(NotesEditorIntent.SaveNow)
        runCurrent()
        // Read the state HERE and not after advancing further: `pushUiState` clears a
        // persistence error on its next pass by design (DraftMviViewModel:165-168 — "A
        // persistence error set by [save] is cleared the same way — the user has moved
        // on"). Letting the 500ms autosave debounce fire in between wipes the error, so a
        // control that waits past it is asserting nothing about the save at all.
        assertNotNull(
            vm.stateFlow.value.error,
            "the note was saved but its links were not, so the backlinks feature silently " +
                "did nothing — the comment above that line calls it the write path for " +
                "exactly this, and the save reported success; link writes seen: " +
                "${notesRepo.linkWrites}, state was ${vm.stateFlow.value}",
        )
        assertTrue(
            vm.stateFlow.value.isDirty,
            "the draft is still dirty: the user's work was not fully persisted, so " +
                "clearing the flag would claim otherwise",
        )
    }

    @Test
    fun `a save whose links were written reports no error`() = runTest {
        // The negative case. Without it, an unwrap on the wrong call — or an unwrap that
        // fired on every save — would pass the control above.
        val notesRepo = CountingNotesRepository()
        notesRepo.seed(note)
        val vm = createVm(notesRepo, backgroundScope)

        vm.openEditor(note.id.value)
        advanceTimeBy(1_000)
        runCurrent()
        vm.onIntent(NotesEditorIntent.EditBody("<p>See [[note://note-2]]</p>"))
        runCurrent()

        vm.onIntent(NotesEditorIntent.SaveNow)
        runCurrent()

        assertNull(vm.stateFlow.value.error, "the write succeeded; an error here would be a false alarm")
        assertEquals(
            1,
            notesRepo.linkWrites,
            "and the links really were written exactly once",
        )
        assertTrue(
            !vm.stateFlow.value.isDirty,
            "so the save really happened and the draft is clean",
        )
    }
}
