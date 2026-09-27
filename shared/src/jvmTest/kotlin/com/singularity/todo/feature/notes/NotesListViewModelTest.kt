package com.singularity.todo.feature.notes

import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.SequenceIdGenerator
import com.singularity.todo.feature.notes.NotesUiState
import com.singularity.todo.feature.notes.presentation.NotesIntent
import com.singularity.todo.feature.notes.presentation.viewmodel.NotesListViewModel
import com.singularity.todo.test.fakes.FakeNotesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Smoke tests for [NotesListViewModel] — verify state initialization and filter changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesListViewModelTest {

    private val fakeNotesRepo = FakeNotesRepository()

    private fun TestScope.createVm() = NotesListViewModel(
        repo = fakeNotesRepo,
        idGen = SequenceIdGenerator("test"),
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
