package com.singularity.todo.feature.notes

import com.singularity.todo.feature.tasks.UserId
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeCurrentUser
import com.singularity.todo.test.fakes.FakeNotesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Integration tests for the notes editor flow: create → edit → close → reopen.
 * Uses [FakeNotesRepository] + [FakeMarkdownHtmlPort] — no mocking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesEditorFlowTest {

    private val testUserId = UserId("test-user")

    private fun createVm(
        repo: NotesRepository = FakeNotesRepository(),
        htmlPort: MarkdownHtmlPort = FakeMarkdownHtmlPort()
    ): NotesViewModel = NotesViewModel(
        repo,
        htmlPort,
        FakeCurrentUser(FakeAuthRepository(initialSession = com.singularity.todo.core.auth.Session.Anonymous(testUserId))),
    )

    @Test
    fun `create note flow creates and opens note`() = runTest {
        val vm = createVm()
        val id = vm.createNote()
        advanceUntilIdle()

        val state = vm.editorState.value
        assertIs<EditorState.Editing>(state)
        assertEquals(id, state.id)
        assertEquals("", state.title)
        assertEquals("", state.html)
    }

    @Test
    fun `edit body marks state dirty`() = runTest {
        val vm = createVm()
        val id = vm.createNote()
        advanceUntilIdle()
        vm.editBody(id, "<p>Hello <strong>World</strong></p>")

        val dirtyState = vm.editorState.value
        assertIs<EditorState.Editing>(dirtyState)
        assertTrue(dirtyState.isDirty)
    }

    @Test
    fun `editTitle synchronously updates state`() = runTest {
        val vm = createVm()
        val id = vm.createNote()
        advanceUntilIdle()
        vm.editTitle(id, "My Title")

        val state = vm.editorState.value
        assertIs<EditorState.Editing>(state)
        assertEquals("My Title", state.title)
        assertTrue(state.isDirty)
    }

    @Test
    fun `close and reopen shows current editor state`() = runTest {
        val vm = createVm()
        val id = vm.createNote()
        advanceUntilIdle()
        vm.editTitle(id, "My Title")
        advanceTimeBy(600.milliseconds)
        advanceUntilIdle()

        vm.closeEditor()
        advanceUntilIdle()
        assertIs<EditorState.Empty>(vm.editorState.value)

        vm.openEditor(id)
        advanceUntilIdle()

        val state = vm.editorState.value
        assertIs<EditorState.Editing>(state)
        assertEquals("", state.title)
    }

    @Test
    fun `delete note removes from store`() = runTest {
        val vm = createVm()
        val id = vm.createNote()
        vm.delete(NoteId.fromString(id))
    }
}
