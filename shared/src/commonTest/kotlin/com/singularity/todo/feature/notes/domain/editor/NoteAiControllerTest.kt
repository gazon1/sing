package com.singularity.todo.feature.notes.domain.editor

import com.singularity.todo.feature.notes.NoteAiResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NoteAiControllerTest {

    @Test
    fun `isAvailable returns false when improveNote is null`() {
        val controller = NoteAiController(improveNote = null)
        assertFalse(controller.isAvailable)
    }

    @Test
    fun `isAvailable returns true when improveNote is provided`() {
        val controller = NoteAiController(
            improveNote = { _, _ -> Result.success(NoteAiResult.Improved("t", "<p>b</p>")) },
        )
        assertTrue(controller.isAvailable)
    }

    @Test
    fun `improve returns Error when unavailable`() = runTest {
        val controller = NoteAiController(improveNote = null)

        val result = controller.improve("title", "<p>html</p>")

        assertIs<NoteAiResult.Error>(result)
        assertEquals("AI unavailable", result.message)
    }

    @Test
    fun `improve maps success to Improved`() = runTest {
        val controller = NoteAiController(
            improveNote = { title, html ->
                Result.success(NoteAiResult.Improved("$title (improved)", "<p>improved $html</p>"))
            },
        )

        val result = controller.improve("My Title", "<p>original body</p>")

        assertIs<NoteAiResult.Improved>(result)
        assertEquals("My Title (improved)", result.title)
        assertEquals("<p>improved <p>original body</p></p>", result.body)
    }

    @Test
    fun `improve maps failure to Error`() = runTest {
        val controller = NoteAiController(
            improveNote = { _, _ -> Result.failure(IllegalStateException("boom")) },
        )

        val result = controller.improve("title", "<p>html</p>")

        assertIs<NoteAiResult.Error>(result)
        assertEquals("boom", result.message)
    }
}
