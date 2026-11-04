package com.singularity.todo.feature.nav

import androidx.compose.ui.graphics.vector.ImageVector
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.shell.AppMenu
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The rules the destination icon map has to keep.
 *
 * A destination is recognised by its glyph before its label is read, so the map
 * is a small kind of typography: one shape, one meaning. The map is a `when` over
 * a sealed hierarchy, which means it compiles whatever glyphs it returns — these
 * are the only things that say the glyphs still differ.
 *
 * The rules, in the order the user meets them:
 *
 * 1. an entry of the menu has a glyph of its own — `MenuIconsTest`, in the shell,
 *    beside the menu it reads;
 * 2. a tab of the bottom bar has one too ([theBottomBarTabsHaveTheirOwnGlyphs]);
 * 3. the agenda's start routes do not fall back onto one glyph, and none of them
 *    is the tasks' glyph ([theAgendaRoutesArePairwiseDistinct],
 *    [theAgendaRoutesDoNotReuseTheTasksGlyph]);
 * 4. nothing is drawn with the "done" tick ([noDestinationIsDrawnWithTheDoneTick]).
 *
 * Rule 3 is the regression this file exists for: the agenda branch used to end in
 * an `else` that returned the tasks' glyph, so every drill-down — a project, a
 * tag, the blocked and overdue lists — read as "Tasks".
 */
@Tag("fast")
class DestinationIconsTest {
    /** The glyphs the tasks are drawn with; the agenda must not borrow them. */
    private val tasksGlyphs: Set<String> =
        setOf(
            AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create()).icon,
        ).map { it.glyphName() }.toSet()

    /**
     * Every agenda start route, as the destination it opens.
     *
     * All thirteen are here, not only the ones that need no payload: a route left
     * out of this list is a route the duplicate check cannot fail on, which is how
     * the drill-downs came to share one glyph in the first place.
     */
    private val agendaRoutes: List<AppDestination.AgendaGraph> =
        listOf(
            AgendaStartRoute.Inbox,
            AgendaStartRoute.Today,
            AgendaStartRoute.Upcoming,
            AgendaStartRoute.Project(ProjectId("p1")),
            AgendaStartRoute.Tag(TagId("t1")),
            AgendaStartRoute.Tags(setOf(TagId("t1"))),
            AgendaStartRoute.SavedAgendaList,
            AgendaStartRoute.SavedAgendaResults(SavedAgendaViewId("v1")),
            AgendaStartRoute.SavedAgendaEdit(SavedAgendaViewId("v1")),
            AgendaStartRoute.SavedAgendaCreate,
            AgendaStartRoute.Blocked,
            AgendaStartRoute.Stagnant,
            AgendaStartRoute.Overdue,
        ).map { AppDestination.AgendaGraph(it) }

    @Test
    fun theBottomBarTabsHaveTheirOwnGlyphs() {
        val shared = DestinationKind.tabs.groupBy { it.icon.glyphName() }.filterValues { it.size > 1 }
        assertTrue(
            shared.isEmpty(),
            "Bottom-bar tabs sharing one glyph: ${shared.values.flatten().map { it.title }}",
        )
    }

    @Test
    fun theAgendaRoutesArePairwiseDistinct() {
        val shared = agendaRoutes.groupBy { it.icon.glyphName() }.filterValues { it.size > 1 }
        assertTrue(
            shared.isEmpty(),
            "Agenda start routes sharing one glyph: ${shared.values.flatten().map { it.title }}",
        )
    }

    @Test
    fun theAgendaRoutesDoNotReuseTheTasksGlyph() {
        val borrowed =
            agendaRoutes.filter { it.icon.glyphName() in tasksGlyphs }.map { it.title }
        assertTrue(
            borrowed.isEmpty(),
            "Agenda start routes drawn with the tasks' glyph: $borrowed",
        )
    }

    @Test
    fun noDestinationIsDrawnWithTheDoneTick() {
        val drawn =
            AppMenu.allDestinations.map { it.title to it.icon } +
                DestinationKind.tabs.map { it.title to it.icon } +
                agendaRoutes.map { it.title to it.icon }
        val offenders = drawn.filter { (_, icon) -> icon.glyphName() == "Check" }.map { it.first }
        assertTrue(
            offenders.isEmpty(),
            "Destinations drawn with the done tick: $offenders",
        )
    }

    /**
     * The name of a glyph, without the style it is drawn in.
     *
     * "Filled.Archive" and "Outlined.Archive" are one glyph in two styles, so the
     * style is dropped before the comparison: the same shape twice is the defect,
     * whichever style it is drawn in.
     */
    private fun ImageVector.glyphName(): String = name.substringAfterLast('.')
}
