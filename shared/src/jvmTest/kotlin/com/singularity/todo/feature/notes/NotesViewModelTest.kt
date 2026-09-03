package com.singularity.todo.feature.notes

import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tasks.UserId
import com.singularity.todo.test.fakes.FakeSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {

    private val testUserId = UserId("test-user")

    private fun createVm(
        store: NotesStore = FakeNotesStore(),
        htmlPort: MarkdownHtmlPort = FakeMarkdownHtmlPort()
    ): NotesViewModel {
        return NotesViewModel(
            store,
            htmlPort,
            FakeSettingsRepository(testUserId.value)
        )
    }

    @Test
    fun `open editor loads note and converts body`() = runTest {
        val store = FakeNotesStore().apply {
            val id = NoteId.fromString("n1")
            seed("n1", Note(
                id = id,
                userId = testUserId,
                title = "My Note",
                bodyMarkdown = "# Hello",
                createdAt = Clock.now(),
                updatedAt = Clock.now()
            ))
        }
        val vm = createVm(store, object : MarkdownHtmlPort {
            override fun toHtml(md: String) = "<h1>Hello</h1>"
            override fun toMarkdown(html: String) = "# Hello"
        })

        vm.openEditor("n1")
        advanceUntilIdle()

        val state = vm.editorState.value
        assertIs<EditorState.Editing>(state)
        assertEquals("My Note", state.title)
        assertEquals("<h1>Hello</h1>", state.html)
    }

    @Test
    fun `createNote sets editing state`() = runTest {
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
    fun `editTitle updates state and marks dirty`() = runTest {
        val vm = createVm()
        val id = vm.createNote()
        advanceUntilIdle()

        vm.editTitle(id, "New Title")

        val state = vm.editorState.value
        assertIs<EditorState.Editing>(state)
        assertEquals("New Title", state.title)
        assertEquals(true, state.isDirty)
    }

    @Test
    fun `closeEditor resets state to Empty`() = runTest {
        val vm = createVm()
        vm.createNote()
        advanceUntilIdle()

        vm.closeEditor()

        assertIs<EditorState.Empty>(vm.editorState.value)
    }

    @Test
    fun `state emits Empty when no notes`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        val state = vm.state.value
        assertIs<NotesUiState.Empty>(state, "Expected Empty but got: $state")
    }

    @Test
    fun `state emits Content when notes exist`() = runTest {
        val store = FakeNotesStore().apply {
            seed("n1", Note(
                id = NoteId.fromString("n1"),
                userId = testUserId,
                title = "Note 1",
                bodyMarkdown = null,
                createdAt = Clock.now(),
                updatedAt = Clock.now()
            ))
        }
        val vm = createVm(store = store)
        advanceUntilIdle()

        val state = vm.state.value
        assertIs<NotesUiState.Content>(state)
        assertEquals(1, state.notes.size)
    }
}
