package com.singularity.todo.feature.notes.domain

import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NoteKind
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TemplatePickerTest {

    private val repo = FakeNotesRepository(FakeProfileAwareCurrentUser())
    private val picker = TemplatePicker(repo)

    @Test
    fun `templates returns only Template-kind notes`() = runTest {
        // Plain note
        repo.createNoteWithTitle("Plain note")
        // Template note (via saveAsTemplate)
        val templateId = repo.createNoteWithTitle("My template").getOrThrow()
        repo.saveAsTemplate(templateId)
        // Another plain note
        repo.createNoteWithTitle("Another plain")

        val templates = picker.templates().first()
        assertEquals(1, templates.size)
        assertEquals("My template", templates[0].title)
        assertEquals(NoteKind.Template, templates[0].kind)
    }

    @Test
    fun `apply creates new note from template with target title`() = runTest {
        val templateId = repo.createWithContent(
            NoteId("tpl-1"),
            title = "Daily standup",
            bodyMarkdown = "1. What I did yesterday\n2. What I'm doing today\n3. Blockers",
            bodyHtml = "<p>1. What I did yesterday</p>",
        ).getOrThrow()
        repo.saveAsTemplate(templateId)

        val newId = picker.apply(templateId, "Sprint 42 standup", null).getOrThrow()

        val newNote = repo.get(newId)
        assertNotNull(newNote)
        assertEquals("Sprint 42 standup", newNote.title)
        assertEquals(NoteKind.Plain, newNote.kind)
        assertTrue(newNote.bodyMarkdown?.contains("What I'm doing today") == true)
    }

    @Test
    fun `apply creates Daily note when targetDateKey is provided`() = runTest {
        val templateId = repo.createWithContent(
            NoteId("tpl-2"),
            title = "Morning journal",
            bodyMarkdown = "# Morning journal\n\n## Gratitude\n\n## Goals",
            bodyHtml = "<h1>Morning journal</h1>",
        ).getOrThrow()
        repo.saveAsTemplate(templateId)

        val newId = picker.apply(templateId, "Morning journal", "2024-03-15").getOrThrow()

        val newNote = repo.get(newId)
        assertNotNull(newNote)
        assertEquals("2024-03-15 — Morning journal", newNote.title)
        assertEquals(NoteKind.Daily, newNote.kind)
        assertTrue(newNote.bodyMarkdown?.contains("Gratitude") == true)
    }

    @Test
    fun `apply returns failure when template does not exist`() = runTest {
        val result = picker.apply(NoteId("nonexistent"), "Title", null)
        assertTrue(result.isFailure)
    }

    @Test
    fun `saveAsTemplate changes note kind to Template`() = runTest {
        val noteId = repo.createNoteWithTitle("Convert me").getOrThrow()
        assertEquals(NoteKind.Plain, repo.get(noteId)?.kind)

        picker.saveAsTemplate(noteId)

        assertEquals(NoteKind.Template, repo.get(noteId)?.kind)
    }

    @Test
    fun `isTemplate returns true for Template notes`() {
        val templateNote = Note(
            id = NoteId("t1"),
            userId = com.singularity.todo.core.ids.UserId("u1"),
            title = "T",
            kind = NoteKind.Template,
            createdAt = kotlin.time.Clock.System.now(),
            updatedAt = kotlin.time.Clock.System.now(),
        )
        val plainNote = Note(
            id = NoteId("n1"),
            userId = com.singularity.todo.core.ids.UserId("u1"),
            title = "N",
            kind = NoteKind.Plain,
            createdAt = kotlin.time.Clock.System.now(),
            updatedAt = kotlin.time.Clock.System.now(),
        )
        assertTrue(picker.isTemplate(templateNote))
        assertFalse(picker.isTemplate(plainNote))
    }
}
