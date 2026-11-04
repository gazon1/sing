package com.singularity.todo.feature.nav

import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.AgendaCreatePrefill
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.state.DueDateOption
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a task started from an agenda list has to carry to belong to that list.
 *
 * An empty list offers the "Create a task" button, and an empty list has no
 * section to ask what belongs in it — [com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator]
 * drops every section that matched nothing. The route is the only thing left
 * that knows, so it is the thing this suite pins.
 *
 * The `when` in [toCreatePrefill] is exhaustive over [AgendaStartRoute]: a new
 * route does not compile until it is given a decision here.
 */
@Tag("fast")
class AgendaCreatePrefillTest {
    private val project = ProjectId("p1")
    private val work = TagId("work")
    private val home = TagId("home")

    @Test
    fun `a task started from a tab carries the date bucket its rows live in`() {
        assertEquals(
            AgendaCreatePrefill(),
            AgendaStartRoute.Inbox.toCreatePrefill(),
            "the Inbox holds undated tasks, so its task carries no date",
        )
        assertEquals(
            AgendaCreatePrefill(dueDate = DueDateOption.Today),
            AgendaStartRoute.Today.toCreatePrefill(),
            "a task started from Today is due today, exactly like the shell's FAB",
        )
        assertEquals(
            AgendaCreatePrefill(dueDate = DueDateOption.Tomorrow),
            AgendaStartRoute.Upcoming.toCreatePrefill(),
            "an undated task would land in the Inbox, and Upcoming would stay empty",
        )
    }

    @Test
    fun `a task started from a project or tag list carries that scope`() {
        assertEquals(
            AgendaCreatePrefill(projectId = project),
            AgendaStartRoute.Project(project).toCreatePrefill(),
            "the list filters by project, so the task has to be in it",
        )
        assertEquals(
            AgendaCreatePrefill(tagIds = setOf(work)),
            AgendaStartRoute.Tag(work).toCreatePrefill(),
            "the list filters by tag, so the task has to carry it",
        )
        assertEquals(
            AgendaCreatePrefill(tagIds = setOf(work, home)),
            AgendaStartRoute.Tags(setOf(work, home)).toCreatePrefill(),
            "the multi-tag list carries every tag it filters by",
        )
    }

    @Test
    fun `a list that cannot hold a new task offers no prefill`() {
        val listsThatCannotHoldOne =
            listOf(
                AgendaStartRoute.Blocked,
                AgendaStartRoute.Stagnant,
                AgendaStartRoute.Overdue,
                AgendaStartRoute.SavedAgendaList,
                AgendaStartRoute.SavedAgendaResults(SavedAgendaViewId("v1")),
                AgendaStartRoute.SavedAgendaEdit(SavedAgendaViewId("v1")),
                AgendaStartRoute.SavedAgendaCreate,
            )

        listsThatCannotHoldOne.forEach { route ->
            assertNull(
                route.toCreatePrefill(),
                "$route cannot hold a task the create editor could make, so it offers no button",
            )
        }
    }
}
