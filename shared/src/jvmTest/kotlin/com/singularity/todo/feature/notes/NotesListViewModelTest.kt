package com.singularity.todo.feature.notes

import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.feature.notes.NotesUiState
import com.singularity.todo.feature.notes.presentation.NotesIntent
import com.singularity.todo.feature.notes.presentation.viewmodel.NotesListViewModel
import com.singularity.todo.test.fakes.FakeNotesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Smoke tests for [NotesListViewModel] — verify state initialization and filter changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesListViewModelTest {

    private val fakeNotesRepo = FakeNotesRepository()

    private fun TestScope.createVm() = NotesListViewModel(
        repo = fakeNotesRepo,
        scope = testScope(backgroundScope),
    )

    /** Reads the one state snapshot the screen actually renders. */
    private fun NotesListViewModel.listState() =
        (state.value as? NotesUiState.Content)?.list ?: error("expected Content, got ${state.value}")

    @Test
    fun `initial filter is All`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        testScheduler.runCurrent()
        assertEquals(NoteFilter.All, vm.listState().filter)
    }

    @Test
    fun `initial sort order is UpdatedDesc`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        testScheduler.runCurrent()
        assertEquals(NoteSortOrder.UpdatedDesc, vm.listState().sortOrder)
    }

    /**
     * The id the screen opens the editor on must be the id the repository persisted.
     * Returning a locally generated one names a note that does not exist — the empty
     * state's "Create your first note" button did exactly that, orphaning the note that
     * was really created. See `2026-09-28-mr5-vm-hygiene`.
     */
    @Test
    fun `createNoteWithTitle navigates to the id the repository persisted`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        runCurrent()

        vm.createNoteWithTitle("Fresh note")
        advanceUntilIdle()
        runCurrent()

        val navigated = vm.events.first()
        assertIs<NotesUiEvent.NavigateToEditor>(navigated, "expected a navigation event, got $navigated")
        assertEquals(
            fakeNotesRepo.notes.keys.toList(),
            listOf(navigated.noteId.value),
            "the editor must open the note that was actually created",
        )
    }

    @Test
    fun `setFilter updates filter state`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        testScheduler.runCurrent()
        vm.onIntent(NotesIntent.SetFilter(NoteFilter.Pinned))
        advanceUntilIdle()
        testScheduler.runCurrent()
        assertEquals(NoteFilter.Pinned, vm.listState().filter)
    }
}
