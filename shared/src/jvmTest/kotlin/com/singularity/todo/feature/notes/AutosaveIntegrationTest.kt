package com.singularity.todo.feature.notes

import com.singularity.todo.core.clock.AutosaveScheduler
import com.singularity.todo.core.ids.SequenceIdGenerator
import com.singularity.todo.feature.tasks.UserId
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeCurrentUser
import com.singularity.todo.test.fakes.FakeNotesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Integration tests for the autosave mechanism.
 * Verifies that edits are observable and dirty state is managed correctly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutosaveIntegrationTest {

    private val testUserId = UserId("test-user")

    private fun createVm(
        autosaveScheduler: AutosaveScheduler,
        repo: FakeNotesRepository = FakeNotesRepository(),
        scope: kotlinx.coroutines.CoroutineScope? = null,
    ): NotesViewModel = NotesViewModel(
        repo,
        FakeMarkdownHtmlPort(),
        FakeCurrentUser(FakeAuthRepository(
            com.singularity.todo.core.auth.Session.Anonymous(testUserId)
        )),
        idGen = SequenceIdGenerator(),
        autosaveScheduler = autosaveScheduler,
        scopeOverride = scope,
    )

    @Test
    fun `edit body → marks state dirty`() = runTest {
        val repo = FakeNotesRepository()
        // Scheduler that never fires — confirms dirty state isn't auto-cleared
        val neverScheduler = object : AutosaveScheduler {
            override suspend fun awaitTick() { delay(Long.MAX_VALUE) }
        }

        val vm = createVm(neverScheduler, repo, backgroundScope)
        val id = vm.createNote()
        advanceUntilIdle()

        vm.editBody(id, "<p>Hello</p>")
        advanceUntilIdle()

        val state = vm.editorState.value
        assertTrue(state is EditorState.Editing, "state should be Editing")
        assertTrue(state.isDirty, "isDirty should be true after edit")
    }
}
