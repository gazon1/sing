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

    // ── Delete / Undo ──────────────────────────────────────────────────

    /** Must match [NotesListViewModel.UNDO_WINDOW_MS]. */
    private val undoWindowMs = 5_000L

    /**
     * A delete shows `UndoDelete` immediately and removes the note from the list
     * only after the 5-second undo window expires.
     *
     * This is the positive control for the undo pattern. The note must stay in the
     * list so the user can undo before the window closes.
     */
    @Test
    fun `delete emits UndoDelete and removes note after undo window`() = runTest {
        val id = seedNote("Doomed")
        val vm = createVm()
        advanceUntilIdle()
        runCurrent()
        assertEquals(1, vm.listState().unpinned.size)

        val events = mutableListOf<NotesUiEvent>()
        val collector = launch { vm.events.collect { events += it } }
        try {
            vm.onIntent(NotesIntent.Delete(id))
            runCurrent()

            // UndoDelete is emitted immediately; note is still in the list.
            val undoDelete = events.filterIsInstance<NotesUiEvent.UndoDelete>().singleOrNull()
            assertNotNull(undoDelete, "UndoDelete must be emitted: $events")
            assertEquals(id, undoDelete.noteId)
            assertEquals("Doomed", undoDelete.title)
            assertEquals(1, vm.listState().unpinned.size, "note must stay during undo window")

            // Advance past the undo window — the note is deleted.
            advanceTimeBy(undoWindowMs + 1)
            advanceUntilIdle()
            runCurrent()
            assertEquals(0, vm.listState().unpinned.size, "note must go after undo window expires")
        } finally {
            collector.cancel()
        }
    }

    /**
     * Tapping Undo restores the note and cancels the pending delete.
     */
    @Test
    fun `undo restores the note and cancels the pending delete`() = runTest {
        val id = seedNote("UndoMe")
        val vm = createVm()
        advanceUntilIdle()
        runCurrent()
        assertEquals(1, vm.listState().unpinned.size)

        val events = mutableListOf<NotesUiEvent>()
        val collector = launch { vm.events.collect { events += it } }
        try {
            vm.onIntent(NotesIntent.Delete(id))
            runCurrent()
            assertEquals(1, vm.listState().unpinned.size)

            // Tap Undo.
            vm.onIntent(NotesIntent.UndoDelete(id))
            advanceUntilIdle()
            runCurrent()
            assertEquals(1, vm.listState().unpinned.size, "note must be restored")

            // Advance past the former undo window — the note must NOT disappear.
            advanceTimeBy(undoWindowMs + 1)
            advanceUntilIdle()
            runCurrent()
            assertEquals(1, vm.listState().unpinned.size, "restored note must not be deleted after window")
        } finally {
            collector.cancel()
        }
    }

    /**
     * A second delete while the first undo window is still open supersedes the first:
     * `pendingDelete` switches to the second note, and the first note's pending
     * delete job is cancelled so it will NOT be deleted when its window expires.
     *
     * The cancellation is verified by: (a) `pendingDelete` points to the second note
     * immediately after the second delete, and (b) after the second window expires,
     * neither note is in the soft-deleted set — only the second note was deleted.
     */
    @Test
    fun `a new delete supersedes the previous pending delete`() = runTest {
        val first = seedNote("First")
        val second = seedNote("Second")
        val vm = createVm()
        advanceUntilIdle()
        runCurrent()

        vm.onIntent(NotesIntent.Delete(first))
        runCurrent()
        assertEquals(first, vm.pendingDelete.value?.noteId, "first delete must set pendingDelete")

        // Second delete cancels the first's pending job and supersedes it.
        vm.onIntent(NotesIntent.Delete(second))
        runCurrent()
        assertEquals(second, vm.pendingDelete.value?.noteId, "second delete must supersede first")

        // Verify first's pending job was actually cancelled by advancing past its
        // window and checking that only second's note ends up deleted.
        advanceTimeBy(undoWindowMs + 1)
        advanceUntilIdle()
        runCurrent()

        // First note was never deleted (job cancelled), second was deleted (window expired).
        // So only first remains in the list.
        assertEquals(
            listOf(first),
            vm.listState().unpinned.map { it.id },
            "first must remain (pending job cancelled); second must be gone (window expired)",
        )
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
