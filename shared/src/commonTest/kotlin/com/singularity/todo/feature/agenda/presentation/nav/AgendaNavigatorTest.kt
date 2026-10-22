package com.singularity.todo.feature.agenda.presentation.nav

import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.tasks.domain.model.TaskId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The saved view a task was opened from travels with the route.
 *
 * The agenda is the only place that knows which saved view the person was looking at.
 * By the time the task's detail screen is up, the list is gone — so a reminder set
 * there can only point back at the view if the route carried it.
 */
@Tag("fast")
class AgendaNavigatorTest {

    private fun navigator(
        start: AgendaStartRoute,
        onExit: (AppDestination?) -> Unit,
    ) = AgendaNavigator(NavBackStack(start), onExit)

    @Test
    fun `a task opened from a saved view's list carries that view`() {
        val viewId = SavedAgendaViewId.fromString("v1")
        var opened: AppDestination? = null
        val navigator = navigator(AgendaStartRoute.SavedAgendaResults(viewId)) { opened = it }

        navigator.openTask(TaskId("t1"))

        assertEquals(
            AppDestination.TasksGraph(
                AppDestination.TasksStartRoute.Detail(TaskId("t1"), viewId),
            ),
            opened,
        )
    }

    @Test
    fun `a task opened from a saved view's editor carries that view`() {
        val viewId = SavedAgendaViewId.fromString("v1")
        var opened: AppDestination? = null
        val navigator = navigator(AgendaStartRoute.SavedAgendaEdit(viewId)) { opened = it }

        navigator.openTask(TaskId("t1"))

        val start = (opened as AppDestination.TasksGraph).start as AppDestination.TasksStartRoute.Detail
        assertEquals(viewId, start.sourceViewId)
    }

    @Test
    fun `a task opened from a tab carries no view`() {
        var opened: AppDestination? = null
        val navigator = navigator(AgendaStartRoute.Today) { opened = it }

        navigator.openTask(TaskId("t1"))

        val start = (opened as AppDestination.TasksGraph).start as AppDestination.TasksStartRoute.Detail
        assertNull(start.sourceViewId, "a tab is not a saved view, so there is nothing to point at")
    }
}
