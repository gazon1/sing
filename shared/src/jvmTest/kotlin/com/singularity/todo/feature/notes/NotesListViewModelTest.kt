@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.notes

import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.feature.notes.NotesUiState
import com.singularity.todo.feature.notes.presentation.NotesIntent
import com.singularity.todo.feature.notes.presentation.viewmodel.NotesListViewModel
import com.singularity.todo.test.fakes.FakeNotesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Smoke tests for [NotesListViewModel] — verify state initialization and filter changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("slow")
class NotesListViewModelTest {

    /** Mirrors `NotesListViewModel`'s debounce window; must be ≥ the real one. */
    private val searchDebounceMs = 200L

    private val fakeNotesRepo = FakeNotesRepository()

    private fun TestScope.createVm() = NotesListViewModel(
        repo = fakeNotesRepo,
        scope = testScope(backgroundScope),
    )

    /** Reads the one state snapshot the screen actually renders. */
    private fun NotesListViewModel.listState() =
        (state.value as? NotesUiState.Content)?.list ?: error("expected Content, got ${state.value}")

    /**
     * Creates a plain note through the repository, so it carries the same user id
     * the repository's flows filter on.
     */
    private suspend fun seedNote(title: String): NoteId =
        fakeNotesRepo.createWithContent(NoteId("n-${title.hashCode()}"), title, "", "").getOrThrow()

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

    @Test
    fun `archive intent removes the note from the All list`() = runTest {
        val id = seedNote("First")
        val vm = createVm()
        advanceUntilIdle()
        runCurrent()
        assertEquals(listOf(id), vm.listState().unpinned.map { it.id })

        vm.onIntent(NotesIntent.Archive(id))
        advanceUntilIdle()
        runCurrent()

        assertEquals(
            emptyList(),
            vm.listState().unpinned.map { it.id },
            "an archived note must not stay in the All list",
        )
    }

    @Test
    fun `unarchive intent restores the note into the All list`() = runTest {
        val id = seedNote("First")
        fakeNotesRepo.archive(id)
        val vm = createVm()
        advanceUntilIdle()
        runCurrent()

        vm.onIntent(NotesIntent.SetFilter(NoteFilter.Archived))
        advanceUntilIdle()
        runCurrent()
        assertEquals(listOf(id), vm.listState().unpinned.map { it.id })

        vm.onIntent(NotesIntent.Unarchive(id))
        advanceUntilIdle()
        runCurrent()

        vm.onIntent(NotesIntent.SetFilter(NoteFilter.All))
        advanceUntilIdle()
        runCurrent()
        assertEquals(
            listOf(id),
            vm.listState().unpinned.map { it.id },
            "an unarchived note must be back in the All list",
        )
    }

    @Test
    fun `search query is applied after the debounce window`() = runTest {
        seedNote("Groceries")
        val vm = createVm()
        advanceUntilIdle()
        runCurrent()
        val allBefore = vm.listState().unpinned.size
        assertEquals(1, allBefore, "the seeded note must be in the unfiltered list")

        vm.onIntent(NotesIntent.SearchQueryChanged("zzz-no-such-note"))
        // Nothing has settled yet — the debounce has not elapsed.
        runCurrent()
        assertEquals(
            allBefore,
            vm.listState().unpinned.size,
            "the list must not re-query before the debounce window closes",
        )

        advanceTimeBy(searchDebounceMs + 1)
        advanceUntilIdle()
        runCurrent()
        assertEquals(
            emptyList(),
            vm.listState().unpinned,
            "a query that matches nothing must empty the list",
        )
        assertEquals("zzz-no-such-note", vm.listState().searchQuery)
    }

    @Test
    fun `a matching query narrows the list`() = runTest {
        seedNote("Groceries")
        seedNote("Reading list")
        val vm = createVm()
        advanceUntilIdle()
        runCurrent()
        assertEquals(2, vm.listState().unpinned.size)

        vm.onIntent(NotesIntent.SearchQueryChanged("Grocer"))
        advanceTimeBy(searchDebounceMs + 1)
        advanceUntilIdle()
        runCurrent()

        assertEquals(
            listOf("Groceries"),
            vm.listState().unpinned.map { it.title },
            "only the title-matching note may survive the query",
        )
    }

    @Test
    fun `clearing the search query restores the unfiltered list`() = runTest {
        seedNote("Groceries")
        val vm = createVm()
        advanceUntilIdle()
        runCurrent()
        val all = vm.listState().unpinned.size
        assertEquals(1, all)

        vm.onIntent(NotesIntent.SearchQueryChanged("zzz-no-such-note"))
        advanceTimeBy(searchDebounceMs + 1)
        advanceUntilIdle()
        runCurrent()
        assertEquals(emptyList(), vm.listState().unpinned)

        vm.onIntent(NotesIntent.SearchQueryChanged(""))
        advanceTimeBy(searchDebounceMs + 1)
        advanceUntilIdle()
        runCurrent()
        assertEquals(
            all,
            vm.listState().unpinned.size,
            "clearing the query must bring the whole list back",
        )
    }
}
