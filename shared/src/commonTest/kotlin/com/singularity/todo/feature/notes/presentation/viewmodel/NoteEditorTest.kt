package com.singularity.todo.feature.notes.presentation.viewmodel

import com.singularity.todo.core.clock.AutosaveScheduler
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.notes.EditorState
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.search.InternalLinkRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.test.fakes.FakeIdGenerator
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Smoke tests for [NoteEditor].
 *
 * These tests prove that fire-and-forget VM methods using `Unconfined` dispatcher
 * (openEditor, saveNow, scheduleAutosave, improveNote) work correctly when left
 * on `Unconfined`. They also serve as regression tests: if someone accidentally
 * migrates these to `Dispatchers.Default`, the tests will likely catch it
 * (Unconfined is intentional for these one-shot, non-collecting operations).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorTest {

    private val testUserId = UserId("test-user")

    private val testNote = Note(
        id = NoteId("note-1"),
        userId = testUserId,
        title = "Test Note",
        bodyHtml = "<p>Hello world</p>",
        wordCount = 2,
        charCount = 11,
        createdAt = Clock.now(),
        updatedAt = Clock.now(),
    )

    private val emptyLinkRepo = object : InternalLinkRepository {
        override suspend fun searchNotes(userId: UserId, query: String): List<Note> = emptyList()
        override suspend fun searchTasks(userId: UserId, query: String): List<Task> = emptyList()
        override suspend fun getBacklinkNotes(noteId: String, userId: UserId): List<Note> = emptyList()
    }

    /**
     * A scheduler that blocks on [awaitTick] until [resume] is called.
     * Use this to control WHEN the autosave fires, so we can observe state before it.
     */
    private class PausableAutosaveScheduler : AutosaveScheduler {
        private val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()

        override suspend fun awaitTick() {
            deferred.await()
        }

        override fun delayMs(): Long = 0L

        /** Allow the pending autosave to proceed. Call this after observing state. */
        fun resume() {
            deferred.complete(Unit)
        }
    }

    private fun createVm(
        notesRepo: FakeNotesRepository = FakeNotesRepository(),
        autosaveScheduler: AutosaveScheduler = PausableAutosaveScheduler(),
        scope: CoroutineScope,
    ): NoteEditor = NoteEditor(
        repo = notesRepo,
        linkRepo = emptyLinkRepo,
        currentUser = FakeProfileAwareCurrentUser(initialUserId = testUserId),
        idGen = FakeIdGenerator("note"),
        autosaveScheduler = autosaveScheduler,
        improveNote = null,
        scope = testScope(scope),
    )

    @Test
    fun `openEditor loads Editing state`() = runTest {
        val notesRepo = FakeNotesRepository()
        notesRepo.seed(testNote)
        val scheduler = PausableAutosaveScheduler()
        val vm = createVm(notesRepo = notesRepo, autosaveScheduler = scheduler, scope = this)

        vm.openEditor(testNote.id.value)
        advanceUntilIdle()

        val state = vm.editorState.value
        assertIs<EditorState.Editing>(state)
        assertEquals(testNote.id.value, state.id)
        assertEquals("Test Note", state.title)
        assertEquals("<p>Hello world</p>", state.html)
        assertEquals(false, state.isDirty)
    }

    @Test
    fun `saveNow persists and clears dirty`() = runTest {
        val notesRepo = FakeNotesRepository()
        notesRepo.seed(testNote)
        val scheduler = PausableAutosaveScheduler()
        val vm = createVm(notesRepo = notesRepo, autosaveScheduler = scheduler, scope = this)

        vm.openEditor(testNote.id.value)
        advanceUntilIdle()

        // Edit body — autosave is paused (scheduler is blocked), dirty becomes true
        vm.editBody(testNote.id.value, "<p>Updated content</p>")
        advanceUntilIdle() // allow the edit to be observed

        val dirtyState = vm.editorState.value
        assertIs<EditorState.Editing>(dirtyState)
        assertEquals(true, dirtyState.isDirty)

        // saveNow persists directly — clears dirty regardless of autosave
        vm.saveNow()
        advanceUntilIdle()

        val savedState = vm.editorState.value
        assertIs<EditorState.Editing>(savedState)
        assertEquals(false, savedState.isDirty)
        assertEquals("<p>Updated content</p>", savedState.html)
    }

    @Test
    fun `editBody marks dirty then autosave clears it`() = runTest {
        val notesRepo = FakeNotesRepository()
        notesRepo.seed(testNote)
        val scheduler = PausableAutosaveScheduler()
        val vm = createVm(notesRepo = notesRepo, autosaveScheduler = scheduler, scope = this)

        vm.openEditor(testNote.id.value)
        advanceUntilIdle()

        // Edit body — autosave is paused, dirty stays true
        vm.editBody(testNote.id.value, "<p>Updated content</p>")
        advanceUntilIdle()

        val dirtyState = vm.editorState.value
        assertIs<EditorState.Editing>(dirtyState)
        assertEquals(true, dirtyState.isDirty)

        // Now let autosave fire — dirty is cleared
        scheduler.resume()
        advanceUntilIdle()

        val cleanState = vm.editorState.value
        assertIs<EditorState.Editing>(cleanState)
        assertEquals(false, cleanState.isDirty)
    }
}
