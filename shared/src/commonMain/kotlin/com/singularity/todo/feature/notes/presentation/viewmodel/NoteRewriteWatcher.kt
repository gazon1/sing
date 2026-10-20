package com.singularity.todo.feature.notes.presentation.viewmodel

import com.singularity.todo.feature.notes.EditorState.Editing
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.domain.NoteContentMapper
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Watches the note an editor holds, and reports the rewrites that arrive from
 * outside it.
 *
 * The editor owns the content it shows, but the note is not only written by the
 * editor: a proposal applied to it (an AI rewrite the user accepted), a sync
 * from another device — both change the note underneath an open editor.
 * Without a watcher the editor keeps showing the old content, and the next
 * keystroke dispatches the *old* body over the new one: the rewrite is silently
 * undone by the user's next word.
 *
 * Two rules keep the watcher from fighting the user:
 *
 * - **The user's unsaved edits win.** A dirty draft is not reported as a
 *   rewrite. The external rewrite is then lost to the next save, which is the
 *   honest outcome: the user has something to lose, the other writer does not.
 * - **The editor's own saves are not rewrites.** The watcher compares the note
 *   against the draft, so the echo of an autosave changes nothing and no
 *   revision is counted.
 *
 * The watcher only *reports*; the editor decides what to do with the report,
 * because only the editor knows how to re-seed its fields.
 *
 * The scope is **borrowed**, not owned: the watcher cancels its own job in
 * [stop] and never the scope it was handed.
 */
internal class NoteRewriteWatcher(private val repo: NotesRepository, private val scope: CoroutineScope) {
    /** The running watch, if any. Cancelled and replaced by [start]. */
    private var job: Job? = null

    /**
     * Watch [noteId] for rewrites.
     *
     * [current] supplies the draft as it is at the moment the note is
     * observed — the watcher must see the *live* draft, not a snapshot, or it
     * would report rewrites for content the user has since changed.
     *
     * [onRewrite] receives the rewritten note and its body HTML.
     */
    fun start(noteId: NoteId, current: () -> Editing, onRewrite: (Note, String) -> Unit) {
        job?.cancel()
        job = scope.launch {
            repo.observe(noteId).collect { note ->
                if (note == null) return@collect
                val draft = current()
                if (draft.id != note.id.value) return@collect
                val html = note.bodyHtml
                    ?: note.bodyMarkdown?.let { NoteContentMapper.toHtml(it) }
                    ?: ""
                // The echo of our own save, or a note that did not change.
                if (draft.title == note.title && draft.html == html) return@collect
                // The user is typing: their text wins over the other writer's.
                if (draft.isDirty) return@collect
                onRewrite(note, html)
            }
        }
    }

    /** Stop watching. The watcher must not re-seed an editor on its way out. */
    fun stop() {
        job?.cancel()
        job = null
    }
}
