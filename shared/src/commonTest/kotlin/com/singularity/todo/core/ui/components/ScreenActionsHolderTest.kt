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
        assertNull(ScreenActionsHolder().actions)
    }

    @Test
    fun the_published_bar_defaults_to_nothing() {
        val holder = ScreenActionsHolder()
        assertNull(holder.title)
        assertNull(holder.navigationIcon)
        assertFalse(holder.override)
    }

    @Test
    fun selection_state_defaults_to_null() {
        val holder = ScreenActionsHolder()
        assertNull(holder.selectionTitle)
        assertNull(holder.onExitSelection)
    }

    @Test
    fun a_top_level_route_change_keeps_the_published_bar() {
        val holder = ScreenActionsHolder()
        holder.publishBar(owner = Any(), title = {}, navigationIcon = { publishedIcon })
        val publishedTitle = holder.title
        holder.actions = {}
        holder.selectionTitle = "2 selected"
        holder.onExitSelection = {}

        holder.onTopLevelRouteChanged()

        assertTrue(holder.override, "a screen's published bar must survive the navigation it caused")
        assertEquals(
            publishedTitle,
            holder.title,
            "the published title belongs to the screen, not to the shell to erase",
        )
        assertNotNull(holder.navigationIcon)
        assertNull(holder.actions, "the previous screen's buttons are stale after a navigation")
        assertNull(holder.selectionTitle)
        assertNull(holder.onExitSelection)
    }

    @Test
    fun a_publisher_takes_its_own_bar_down_when_it_leaves() {
        val holder = ScreenActionsHolder()
        val owner = Any()
        holder.publishBar(owner = owner, title = {}, navigationIcon = { publishedIcon })
        holder.actions = {}

        holder.retractBar(owner)

        assertNull(holder.title)
        assertNull(holder.navigationIcon)
        assertFalse(holder.override)
        // Actions are the shell's own slots: a header leaving is not what
        // empties them, and taking the header down must not touch them.
        assertNotNull(holder.actions)
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
    fun a_bar_leaving_does_not_empty_the_slots_the_arriving_bar_claimed() {
        val holder = ScreenActionsHolder()
        val leaving = Any()
        val arriving = Any()
        holder.claimActions(leaving)
        holder.actions = {}
        // The arriving bar claims the slots as its composition enters, and
        // fills them from its SideEffect.
        holder.claimActions(arriving)
        holder.actions = {}

        // The leaving bar is disposed later, once the transition has played.
        holder.clearActions(leaving)

        assertNotNull(
            holder.actions,
            "a bar leaving during a transition must not empty the arriving screen's action slots",
        )

        // The arriving bar's own disposal empties them.
        holder.clearActions(arriving)
        assertNull(holder.actions)
    }

    @Test
    fun a_bar_that_never_claimed_the_slots_leaves_them_to_the_bar_that_did() {
        val holder = ScreenActionsHolder()
        val bar = Any()
        holder.claimActions(bar)
        holder.actions = {}

        // The calendar publishes a header only and claims no slots; the bar that
        // claimed them still owns them, so its leave still empties them.
        holder.publishBar(owner = Any(), title = {})
        holder.clearActions(bar)

        assertNull(holder.actions, "the previous bar's leave is still the one that empties its slots")
    }
}
