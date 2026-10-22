@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.notes.presentation.viewmodel

import com.singularity.todo.feature.notes.EditorState.Editing
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.domain.NoteContentMapper
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.singularity.todo.core.text.Revisioned
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The watcher that reports rewrites arriving at an open note editor.
 *
 * The editor owns the content it shows, but the note underneath it is not only written
 * by the editor: a proposal applied to it, a sync from another device. These tests pin
 * the rules that decide what the watcher does with such a rewrite:
 *
 * - a clean draft learns about it — the editor must not keep showing the old content,
 *   or the user's next word dispatches the old body over the new one;
 * - a dirty draft does not — the user's unsaved edits win over the other writer;
 * - the echo of the editor's own save is not a rewrite — it changed nothing.
 *
 * The draft is handed to the watcher as a lambda, so the comparison is always against
 * what the user has typed *now*. The last test in this file pins that property.
 *
 * ## The collector runs on `backgroundScope`, and `runCurrent()` drives it
 *
 * The watcher collects the repository's flow on the scope it is given, and the
 * scope here is the test's `backgroundScope`. `runCurrent()` is what runs that
 * collector: `advanceUntilIdle()` advances the virtual clock and runs the
 * *test's own* tasks, but the background work is not among them — a collector
 * that waits for a flow stays suspended, and nothing wakes it. A test that
 * waits for a rewrite with `advanceUntilIdle()` waits for a report that never
 * comes, and the assertion that should have caught a broken watcher passes
 * because the watcher was never asked.
 */
@Tag("fast")
class NoteRewriteWatcherTest {

    private val noteId = NoteId("note-1")
    private val userId = TestUsers.DEFAULT

    /**
     * A note owned by the current user, so the user-scoped flow of the fake repository
     * actually emits it.
     */
    private fun makeNote(
        id: NoteId = noteId,
        title: String = "Test Note",
        bodyHtml: String? = "<p>Hello world</p>",
        bodyMarkdown: String? = null,
    ): Note = Note(
        id = id,
        userId = userId,
        title = title,
        bodyHtml = bodyHtml,
        bodyMarkdown = bodyMarkdown,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    @Test
    fun `a rewrite of a clean draft is reported with the note and its html`() = runTest {
        val repo = FakeNotesRepository()
        val original = makeNote()
        repo.seed(original)
        val reports = mutableListOf<Pair<Note, String>>()
        val draft = Editing(
            id = noteId.value,
            title = Revisioned.initial("Test Note"),
            html = Revisioned.initial("<p>Hello world</p>"),
        )

        val watcher = NoteRewriteWatcher(repo, backgroundScope)
        watcher.start(noteId, { draft }) { rewrittenNote, html -> reports += rewrittenNote to html }
        runCurrent()

        // The note is rewritten from outside the editor.
        val rewritten = original.copy(title = "Rewritten", bodyHtml = "<p>Rewritten body</p>")
        repo.seed(rewritten)
        runCurrent()

        assertEquals(1, reports.size, "the rewrite of a clean draft must be reported once")
        assertEquals(rewritten, reports[0].first, "the report must carry the rewritten note")
        assertEquals("<p>Rewritten body</p>", reports[0].second, "the report must carry the note's html")
    }

    @Test
    fun `a rewrite of a dirty draft is not reported`() = runTest {
        val repo = FakeNotesRepository()
        val original = makeNote()
        repo.seed(original)
        val reports = mutableListOf<Pair<Note, String>>()
        // The user is typing: their text is not saved yet.
        val draft = Editing(
            id = noteId.value,
            title = Revisioned.initial("Test Note"),
            html = Revisioned.initial("<p>Mine</p>"),
            isDirty = true,
        )

        val watcher = NoteRewriteWatcher(repo, backgroundScope)
        watcher.start(noteId, { draft }) { rewrittenNote, html -> reports += rewrittenNote to html }
        runCurrent()

        repo.seed(original.copy(bodyHtml = "<p>Theirs</p>"))
        runCurrent()

        assertTrue(reports.isEmpty(), "the user's unsaved edits win: a dirty draft is not a rewrite")
    }

    @Test
    fun `the echo of the editor's own save is not reported`() = runTest {
        val repo = FakeNotesRepository()
        val reports = mutableListOf<Pair<Note, String>>()
        val draft = Editing(
            id = noteId.value,
            title = Revisioned.initial("Test Note"),
            html = Revisioned.initial("<p>Hello world</p>"),
        )

        val watcher = NoteRewriteWatcher(repo, backgroundScope)
        watcher.start(noteId, { draft }) { rewrittenNote, html -> reports += rewrittenNote to html }
        runCurrent()

        // The editor's own autosave writes back exactly what the draft holds.
        repo.seed(makeNote())
        runCurrent()

        assertTrue(reports.isEmpty(), "the echo of the editor's own save changes nothing and is not a rewrite")
    }

    @Test
    fun `a note whose id differs from the draft's id is ignored`() = runTest {
        val repo = FakeNotesRepository()
        repo.seed(makeNote())
        val reports = mutableListOf<Pair<Note, String>>()
        // The draft belongs to another note than the one the repository emits.
        val draft = Editing(
            id = "note-2",
            title = Revisioned.initial("Test Note"),
            html = Revisioned.initial("<p>Hello world</p>"),
        )

        val watcher = NoteRewriteWatcher(repo, backgroundScope)
        watcher.start(noteId, { draft }) { rewrittenNote, html -> reports += rewrittenNote to html }
        runCurrent()

        repo.seed(makeNote(bodyHtml = "<p>Rewritten body</p>"))
        runCurrent()

        assertTrue(reports.isEmpty(), "a note that is not the one the draft edits is not this editor's business")
    }

    @Test
    fun `a note without html is reported with its markdown converted to html`() = runTest {
        val repo = FakeNotesRepository()
        val markdown = "Hello **world**"
        val expectedHtml = NoteContentMapper.toHtml(markdown)
        repo.seed(makeNote(bodyHtml = null, bodyMarkdown = markdown))
        val reports = mutableListOf<Pair<Note, String>>()
        // The editor already shows the converted markdown — so the note's own emission
        // is the echo of what the editor holds, and only a later rewrite is a rewrite.
        val draft = Editing(
            id = noteId.value,
            title = Revisioned.initial("Test Note"),
            html = Revisioned.initial(expectedHtml),
        )

        val watcher = NoteRewriteWatcher(repo, backgroundScope)
        watcher.start(noteId, { draft }) { rewrittenNote, html -> reports += rewrittenNote to html }
        runCurrent()

        assertTrue(reports.isEmpty(), "the note already matches the draft, so there is nothing to report")

        repo.seed(makeNote(bodyHtml = null, bodyMarkdown = "Replaced body"))
        runCurrent()

        assertEquals(1, reports.size, "the rewrite must be reported once")
        assertEquals(
            NoteContentMapper.toHtml("Replaced body"),
            reports[0].second,
            "the markdown must be converted to html",
        )
    }

    @Test
    fun `a note without html and without markdown is reported with an empty body`() = runTest {
        val repo = FakeNotesRepository()
        repo.seed(makeNote(bodyHtml = null, bodyMarkdown = null))
        val reports = mutableListOf<Pair<Note, String>>()
        val draft = Editing(
            id = noteId.value,
            title = Revisioned.initial("Test Note"),
            html = Revisioned.initial("<p>Hello world</p>"),
        )

        val watcher = NoteRewriteWatcher(repo, backgroundScope)
        watcher.start(noteId, { draft }) { rewrittenNote, html -> reports += rewrittenNote to html }
        runCurrent()

        assertEquals(1, reports.size, "the emptied note must be reported")
        assertEquals("", reports[0].second, "a note without html and without markdown has an empty body")
    }

    @Test
    fun `the draft is read live, not snapshotted when the watch starts`() = runTest {
        val repo = FakeNotesRepository()
        val reports = mutableListOf<Pair<Note, String>>()
        var draft = Editing(
            id = noteId.value,
            title = Revisioned.initial("Test Note"),
            html = Revisioned.initial("<p>Mine</p>"),
        )

        val watcher = NoteRewriteWatcher(repo, backgroundScope)
        watcher.start(noteId, { draft }) { rewrittenNote, html -> reports += rewrittenNote to html }
        runCurrent()

        // The editor has meanwhile taken the rewrite's content into its own draft.
        draft = Editing(
            id = noteId.value,
            title = Revisioned.initial("Test Note"),
            html = Revisioned.initial("<p>Theirs</p>"),
        )
        repo.seed(makeNote(bodyHtml = "<p>Theirs</p>"))
        runCurrent()

        assertTrue(
            reports.isEmpty(),
            "the watcher must compare the note against the live draft: a snapshot taken at start " +
                "would report a rewrite the editor has already applied",
        )
    }

    @Test
    fun `stop silences the watcher`() = runTest {
        val repo = FakeNotesRepository()
        val original = makeNote()
        repo.seed(original)
        val reports = mutableListOf<Pair<Note, String>>()
        val draft = Editing(
            id = noteId.value,
            title = Revisioned.initial("Test Note"),
            html = Revisioned.initial("<p>Hello world</p>"),
        )

        val watcher = NoteRewriteWatcher(repo, backgroundScope)
        watcher.start(noteId, { draft }) { rewrittenNote, html -> reports += rewrittenNote to html }
        runCurrent()

        // The editor closes: the watcher must not re-seed an editor on its way out.
        watcher.stop()
        runCurrent()

        repo.seed(original.copy(bodyHtml = "<p>Rewritten body</p>"))
        runCurrent()

        assertTrue(reports.isEmpty(), "a stopped watcher reports nothing")
    }
}
