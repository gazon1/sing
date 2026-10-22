package com.singularity.todo.feature.nav

import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The key an entry's saved state is filed under.
 *
 * The key has to name the screen, not the route. A route's shape changes — a field is
 * added, a parameter is dropped — and a key built from the route's `toString()` changes
 * with it, so the saved state of an open screen is lost after an update.
 *
 * The property that matters: **two routes that show the same thing get the same key,
 * whatever else they carry.**
 */
@Tag("fast")
class NavContentKeyTest {

    @Test
    fun `a task's key is its id and nothing else`() {
        val withoutAView = TasksRoute.Detail(TaskId("t1"))
        val withAView = TasksRoute.Detail(TaskId("t1"), SavedAgendaViewId.fromString("v1"))

        assertEquals(
            withoutAView.stableContentKey(),
            withAView.stableContentKey(),
            "the view a task was opened from is context, not identity: a key that " +
                "carried it would change when the context does",
        )
        assertEquals("task:t1", withoutAView.stableContentKey())
    }

    @Test
    fun `two tasks get two keys`() {
        assertNotEquals(
            TasksRoute.Detail(TaskId("t1")).stableContentKey(),
            TasksRoute.Detail(TaskId("t2")).stableContentKey(),
            "two different records must not share one saved state",
        )
    }

    @Test
    fun `a note's key is its id`() {
        assertEquals("note:n1", NotesRoute.Preview(NoteId("n1")).stableContentKey())
        assertEquals(
            "note-editor:n1",
            NotesRoute.Editor(NoteId("n1")).stableContentKey(),
            "the editor is a different screen from the preview, so it gets its own key",
        )
    }

    @Test
    fun `a project's key is its id`() {
        assertEquals("project:p1", ProjectsRoute.Detail(ProjectId("p1")).stableContentKey())
    }

    @Test
    fun `a saved view's key is its id`() {
        val viewId = SavedAgendaViewId.fromString("v1")
        assertEquals("saved-view:v1", AgendaStartRoute.SavedAgendaResults(viewId).stableContentKey())
        assertEquals("saved-view-edit:v1", AgendaStartRoute.SavedAgendaEdit(viewId).stableContentKey())
    }

    @Test
    fun `a graph's key names the screen it opens`() {
        assertEquals(
            "tasks-graph:task:t1",
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(TaskId("t1"))).stableContentKey(),
        )
    }

    @Test
    fun `two new-task screens that prefill different dates are two screens`() {
        val plain = AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create()).stableContentKey()
        val today = AppDestination.TasksGraph(
            AppDestination.TasksStartRoute.Create(),
            initialDueDate = LocalDate(2026, 10, 22),
        ).stableContentKey()

        assertNotEquals(
            plain,
            today,
            "one screen's draft is not the other's: the date is part of what the screen shows",
        )
    }

    /**
     * Every destination has a key.
     *
     * The key function is split in two — the leaves and the record-carrying ones — so
     * this test is what says that none of them was left out: a destination that reaches
     * the wrong half fails here instead of failing on screen.
     */
    @Test
    fun `every destination has a content key`() {
        val destinations = listOf(
            AppDestination.Plans,
            AppDestination.Pomodoro,
            AppDestination.Statistics,
            AppDestination.Calendar,
            AppDestination.Notes,
            AppDestination.AiChat,
            AppDestination.Search,
            AppDestination.Archive,
            AppDestination.Settings,
            AppDestination.AiUsage,
            AppDestination.ProfileSwitcher,
            AppDestination.AttachAccount,
            AppDestination.ChangePassword,
            AppDestination.MagicLinkConfirm(),
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create()),
            AppDestination.TasksByProject(ProjectId("p1")),
            AppDestination.ProjectEditor(ProjectId("p1")),
            AppDestination.ProjectDetail(ProjectId("p1")),
            AppDestination.AttachmentViewer(AttachmentId("a1")),
            AppDestination.ProjectsGraph(AppDestination.ProjectsStartRoute.List),
            AppDestination.NotesGraph(AppDestination.NotesStartRoute.List),
            AppDestination.CalendarGraph(),
            AppDestination.AgendaGraph(AgendaStartRoute.Inbox),
        )

        destinations.forEach { destination ->
            assertTrue(
                destination.stableContentKey().isNotBlank(),
                "$destination has no content key — a screen without one loses its saved state",
            )
        }
    }
}
