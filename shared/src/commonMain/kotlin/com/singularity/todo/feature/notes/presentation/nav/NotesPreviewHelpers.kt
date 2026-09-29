package com.singularity.todo.feature.notes.presentation.nav

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.feature.nav.NotesRoute
import com.singularity.todo.feature.notes.NoteId

/**
 * A [NotesNavigator] subclass with all navigation methods as no-ops.
 * Used in @Preview composables to avoid needing a real [NavBackStack].
 *
 * @see NotesPreviewWrapper
 */
class PreviewNotesNavigator(
    private val onOpenTask: (com.singularity.todo.feature.tasks.domain.model.TaskId) -> Unit = {},
) : NotesNavigator(
        backStack = NavBackStack<NotesRoute>(NotesRoute.List, NotesRoute.List),
        onExitGraph = {},
    ) {
    override fun openPreview(noteId: NoteId) { /* no-op for preview */ }
    override fun openEditor(noteId: NoteId?) { /* no-op for preview */ }
    override fun openTask(taskId: com.singularity.todo.feature.tasks.domain.model.TaskId) {
        onOpenTask(taskId)
    }
    override fun back() { /* no-op for preview */ }
    override fun closeGraph() { /* no-op for preview */ }
}

/**
 * Provides [PreviewNotesNavigator] to descendant @Preview composables.
 *
 * Usage:
 * ```
 * @Preview
 * @Composable
 * private fun NotePreviewScreen_Preview() = NotesPreviewWrapper {
 *     NotePreviewScreen(route = NotesRoute.Preview(NoteId("n1")))
 * }
 * ```
 */
@Composable
fun NotesPreviewWrapper(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalNotesNavigator provides PreviewNotesNavigator(),
        content = content,
    )
}
