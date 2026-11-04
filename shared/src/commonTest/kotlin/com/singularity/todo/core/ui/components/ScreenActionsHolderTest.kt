package com.singularity.todo.core.ui.components

import androidx.compose.runtime.Composable
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("fast")
class ScreenActionsHolderTest {
    private val publishedIcon: @Composable () -> Unit = {}

    @Test
    fun actions_default_to_null() {
        assertNull(ScreenActionsHolder().actionsFor(route()))
    }

    @Test
    fun the_published_bar_defaults_to_nothing() {
        val holder = ScreenActionsHolder()
        assertNull(holder.title)
        assertNull(holder.navigationIcon)
        assertNull(holder.headerOwner)
        assertFalse(holder.override)
    }

    @Test
    fun selection_state_defaults_to_null() {
        val holder = ScreenActionsHolder()
        assertNull(holder.selectionTitleFor(route()))
        assertNull(holder.onExitSelectionFor(route()))
    }

    @Test
    fun a_publisher_takes_its_own_bar_down_when_it_leaves() {
        val holder = ScreenActionsHolder()
        val owner = Any()
        holder.publishBar(owner = owner, title = {}, navigationIcon = { publishedIcon })

        holder.retractBar(owner)

        assertNull(holder.title)
        assertNull(holder.navigationIcon)
        assertFalse(holder.override)
    }

    @Test
    fun a_leaving_publisher_does_not_take_down_the_bar_of_the_screen_arriving() {
        val holder = ScreenActionsHolder()
        val leaving = Any()
        val arriving = Any()
        holder.publishBar(owner = leaving, title = {})
        // A navigation composes the arriving screen before the leaving one is
        // disposed, so the arriving screen publishes while the leaving screen
        // still has its DisposableEffect registered.
        holder.publishBar(owner = arriving, title = {})
        val arrivingTitle = holder.title

        holder.retractBar(leaving)

        assertTrue(holder.override, "the leaving screen must not erase the arriving screen's header")
        assertEquals(arrivingTitle, holder.title)

        // The arriving screen's own disposal is what takes the bar down.
        holder.retractBar(arriving)
        assertNull(holder.title)
        assertFalse(holder.override)
    }

    @Test
    fun the_header_under_the_newest_one_takes_over_when_the_newest_leaves() {
        val holder = ScreenActionsHolder()
        val agenda = Any()
        val calendar = Any()
        holder.publishBar(owner = agenda, title = {})
        val agendaTitle = holder.title
        holder.publishBar(owner = calendar, title = {})
        assertTrue(holder.override)

        // The calendar leaves. The agenda is still on screen — it is the shell's
        // start route, so it stays composed — and its header is the one the bar
        // must draw again. This is what brings the greeting back when the user
        // returns to the agenda tab.
        holder.retractBar(calendar)

        assertTrue(holder.override, "the screen behind the newest header is still on screen")
        assertEquals(agendaTitle, holder.title)
        assertEquals(agenda, holder.headerOwner)
    }

    @Test
    fun a_bar_leaving_does_not_empty_the_slots_the_arriving_bar_claimed() {
        val holder = ScreenActionsHolder()
        val leaving = Any()
        val arriving = Any()
        val route = route()
        holder.claimActions(leaving, route)
        holder.publishSlots(leaving, actions = {}, selectionTitle = null, onExitSelection = null)
        // The arriving bar claims the slots as its composition enters, and
        // fills them from its SideEffect.
        holder.claimActions(arriving, route)
        holder.publishSlots(arriving, actions = {}, selectionTitle = null, onExitSelection = null)

        // The leaving bar is disposed later, once the transition has played.
        holder.clearActions(leaving)

        assertNotNull(
            holder.actionsFor(route),
            "a bar leaving during a transition must not empty the arriving screen's action slots",
        )

        // The arriving bar's own disposal empties them.
        holder.clearActions(arriving)
        assertNull(holder.actionsFor(route))
    }

    @Test
    fun a_bar_that_never_claimed_the_slots_leaves_them_to_the_bar_that_did() {
        val holder = ScreenActionsHolder()
        val bar = Any()
        val route = route()
        holder.claimActions(bar, route)
        holder.publishSlots(bar, actions = {}, selectionTitle = null, onExitSelection = null)

        // The calendar publishes a header only and claims no slots; the bar that
        // claimed them still owns them, so its leave still empties them.
        holder.publishBar(owner = Any(), title = {})
        holder.clearActions(bar)

        assertNull(holder.actionsFor(route), "the previous bar's leave is still the one that empties its slots")
    }

    @Test
    fun the_slots_drawn_are_the_ones_of_the_route_the_shell_draws() {
        val holder = ScreenActionsHolder()
        val today = route()
        val calendar = route()
        val todayBar = Any()
        val calendarBar = Any()
        holder.claimActions(todayBar, today)
        holder.publishSlots(todayBar, actions = { }, selectionTitle = null, onExitSelection = null)
        holder.claimActions(calendarBar, calendar)
        holder.publishSlots(calendarBar, actions = { }, selectionTitle = null, onExitSelection = null)

        assertNotNull(holder.actionsFor(today), "the bar of the route the shell draws keeps its buttons")
        assertNotNull(holder.actionsFor(calendar))
    }

    @Test
    fun a_bar_that_re_entered_on_the_same_route_is_the_one_the_shell_draws() {
        val holder = ScreenActionsHolder()
        val route = route()
        val first = Any()
        val second = Any()
        holder.claimActions(first, route)
        holder.publishSlots(first, actions = { }, selectionTitle = "1 selected", onExitSelection = null)
        holder.claimActions(second, route)
        holder.publishSlots(second, actions = { }, selectionTitle = "2 selected", onExitSelection = null)

        assertEquals(
            "2 selected",
            holder.selectionTitleFor(route),
            "the bar that claimed last is the one the shell draws",
        )

        // The second bar is disposed first — it was the one that re-entered,
        // and the first bar is still composed. The first bar's claim is still
        // there, so the bar does not lose its buttons.
        holder.clearActions(second)
        assertNotNull(holder.actionsFor(route), "the bar that is still composed keeps its slots")
        assertEquals("1 selected", holder.selectionTitleFor(route))

        holder.clearActions(first)
        assertNull(holder.actionsFor(route))
    }

    @Test
    fun a_bar_that_leaves_leaves_no_record_of_its_route_behind() {
        val holder = ScreenActionsHolder()
        val route = route()
        val bar = Any()
        holder.claimActions(bar, route)
        holder.publishSlots(bar, actions = { }, selectionTitle = null, onExitSelection = null)

        holder.clearActions(bar)

        // A later bar on the same route finds empty slots, not the previous
        // bar's: the claim and its owner are one record, and the owner is what
        // removes it.
        assertNull(holder.actionsFor(route))
        assertNull(holder.selectionTitleFor(route))
        assertNull(holder.onExitSelectionFor(route))
    }

    @Test
    fun a_bar_that_never_claimed_cannot_publish_slots() {
        val holder = ScreenActionsHolder()
        val route = route()
        holder.publishSlots(Any(), actions = { }, selectionTitle = "2 selected", onExitSelection = null)

        assertNull(holder.actionsFor(route))
        assertNull(holder.selectionTitleFor(route))
    }

    private fun route(): Any = Any()
}
