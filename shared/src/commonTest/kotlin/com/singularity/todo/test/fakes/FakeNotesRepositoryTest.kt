package com.singularity.todo.test.fakes

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FakeNotesRepositoryTest {

    private val userId = UserId("test-user")
    private val testNote = Note(
        id = NoteId("note-1"),
        userId = userId,
        title = "Test Note",
        bodyHtml = "<p>Content</p>",
        createdAt = Clock.now(),
        updatedAt = Clock.now(),
    )

    @Test
    fun `createOverride returns injected failure`() = runTest {
        val repo = FakeNotesRepository()
        repo.createOverride = Result.failure(IllegalStateException("injected"))

        val result = repo.create(testNote)

        assertIs<IllegalStateException>(result.exceptionOrNull())
        assertEquals("injected", result.exceptionOrNull()?.message)
    }

    @Test
    fun `override cleared falls through to runCatching success`() = runTest {
        val repo = FakeNotesRepository()

        repo.createOverride = Result.failure(IllegalStateException("injected"))
        repo.createOverride = null  // clear

        val result = repo.create(testNote)

        assertTrue(result.isSuccess)
        assertEquals("Test Note", result.getOrNull()?.title)
    }
}
