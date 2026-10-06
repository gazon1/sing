@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.notes

import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.feature.notes.NotesUiState
import com.singularity.todo.feature.notes.presentation.NotesIntent
import com.singularity.todo.feature.notes.presentation.viewmodel.NotesListViewModel
import com.singularity.todo.test.fakes.FakeNotesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Smoke tests for [NotesListViewModel] — verify state initialization and filter changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
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

    // ── A delete that did not happen ────────────────────────────────────

    /**
     * A delete that failed reaches the user.
     *
     * The negative control for the dropped `Result`. Pin, archive and unarchive in
     * this same view model all route through `emitError`; delete was the one mutation
     * that did not, so a note that failed to delete vanished from the list with nothing
     * to say why. A delete is the change a user is least likely to retry on their own,
     * which is exactly the case that must not fail quietly.
     */
    @Test
    fun `a delete that fails is reported`() = runTest {
        val id = seedNote("Doomed")
        val vm = createVm()
        advanceUntilIdle()
        runCurrent()
        val events = mutableListOf<NotesUiEvent>()
        val collector = launch { vm.events.collect { events += it } }
        try {
            fakeNotesRepo.deleteOverride = Result.failure(AppError.Persistence("disk is full"))

            vm.onIntent(NotesIntent.Delete(id))
            advanceUntilIdle()
            runCurrent()

            val error = events.filterIsInstance<NotesUiEvent.Error>().singleOrNull()
            assertNotNull(error, "the note was never deleted and nothing said so: $events")
            assertTrue(
                error.message.contains("delete", ignoreCase = true),
                "the message has to name what failed: ${error.message}",
            )
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `a successful delete reports no error`() = runTest {
        val id = seedNote("Doomed")
        val vm = createVm()
        advanceUntilIdle()
        runCurrent()
        val events = mutableListOf<NotesUiEvent>()
        val collector = launch { vm.events.collect { events += it } }
        try {
            vm.onIntent(NotesIntent.Delete(id))
            advanceUntilIdle()
            runCurrent()

            assertEquals(
                emptyList(),
                events.filterIsInstance<NotesUiEvent.Error>(),
                "the delete worked; an error here would teach users to ignore it",
            )
            assertEquals(emptyList(), vm.listState().unpinned.map { it.id })
        } finally {
            collector.cancel()
        }
    }

    /**
     * Bulk delete reports too, and attempts every note even after one fails.
     *
     * Stopping at the first failure leaves the selection describing a half-applied
     * request; reporting only after the last one leaves a user watching the selection
     * clear with no way to tell that some notes did not go. So: every delete runs, and
     * one failure is surfaced.
     */
    @Test
    fun `a bulk delete that fails still attempts the rest and says so`() = runTest {
        val first = seedNote("One")
        val second = seedNote("Two")
        val third = seedNote("Three")
        val vm = createVm()
        advanceUntilIdle()
        runCurrent()
        val events = mutableListOf<NotesUiEvent>()
        val collector = launch { vm.events.collect { events += it } }
        try {
            vm.onIntent(NotesIntent.EnterSelection(first))
            vm.onIntent(NotesIntent.ToggleSelection(second))
            vm.onIntent(NotesIntent.ToggleSelection(third))
            runCurrent()

            fakeNotesRepo.deleteOverride = Result.failure(AppError.Persistence("disk is full"))

            vm.onIntent(NotesIntent.DeleteSelected)
            advanceUntilIdle()
            runCurrent()

            assertNotNull(
                events.filterIsInstance<NotesUiEvent.Error>().singleOrNull(),
                "a bulk delete that failed on every note reported nothing: $events",
            )
        } finally {
            collector.cancel()
        }
    }
}
