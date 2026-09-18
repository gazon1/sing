package com.singularity.todo.feature.notes

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.SequenceIdGenerator
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.notes.presentation.viewmodel.NotesListViewModel
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Smoke tests for [NotesListViewModel] — verify state initialization and filter changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesListViewModelTest {

    private val testUserId = UserId("test-user")
    private val fakeNotesRepo = FakeNotesRepository()

    private fun TestScope.createVm() = NotesListViewModel(
        repo = fakeNotesRepo,
        currentUser = FakeProfileAwareCurrentUser(
            FakeAuthRepository(initialSession = Session.Anonymous(testUserId)),
        ),
        idGen = SequenceIdGenerator("test"),
        scope = backgroundScope,
    )

    @Test
    fun `initial filter is All`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        assertEquals(NoteFilter.All, vm.filter.value)
    }

    @Test
    fun `initial sort order is UpdatedDesc`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        assertEquals(NoteSortOrder.UpdatedDesc, vm.sortOrder.value)
    }

    @Test
    fun `setFilter updates filter state`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.setFilter(NoteFilter.Pinned)
        assertEquals(NoteFilter.Pinned, vm.filter.value)
    }
}
