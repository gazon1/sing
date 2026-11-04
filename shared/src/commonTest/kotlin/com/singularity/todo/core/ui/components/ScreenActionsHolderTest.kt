package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Tag("fast")
class ScreenActionsHolderTest {
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
    fun taking_down_the_newer_bar_restores_the_older_one() {
        val holder = ScreenActionsHolder()
        val first = Any()
        val second = Any()
        val firstTitle: @Composable () -> Unit = {}
        val secondTitle: @Composable () -> Unit = {}
        val firstIcon: @Composable () -> Unit = {}
        val secondIcon: @Composable () -> Unit = {}

        holder.publishBar(first, title = firstTitle, navigationIcon = firstIcon)
        holder.publishBar(second, title = secondTitle, navigationIcon = secondIcon)

        holder.withdrawBar(second)

        assertTrue(holder.override, "the older publication must still own the bar")
        assertSame(firstTitle, holder.title, "the older publication's title must become effective again")
        assertSame(firstIcon, holder.navigationIcon, "the older publication's icon must become effective again")

        holder.withdrawBar(first)

        assertFalse(holder.override, "the last publisher to leave must clear the override")
        assertNull(holder.title, "the last publisher to leave must clear the title")
        assertNull(holder.navigationIcon, "the last publisher to leave must clear the navigation icon")
    }

    @Test
    fun taking_down_one_bar_does_not_clear_a_newer_one() {
        val holder = ScreenActionsHolder()
        val older = Any()
        val newer = Any()
        val newerTitle: @Composable () -> Unit = {}
        val newerIcon: @Composable () -> Unit = {}

        holder.publishBar(older, title = {}, navigationIcon = null)
        holder.publishBar(newer, title = newerTitle, navigationIcon = newerIcon)

        // The older publisher leaves while the newer one is still composed:
        // its disposal must not clear the newer publication.
        holder.withdrawBar(older)

        assertTrue(holder.override, "a stale take-down must not clear the newer publication")
        assertSame(newerTitle, holder.title, "a stale take-down must not clear the newer title")
        assertSame(newerIcon, holder.navigationIcon, "a stale take-down must not clear the newer icon")

        holder.withdrawBar(newer)

        assertFalse(holder.override)
        assertNull(holder.title)
    }

    @Test
    fun taking_down_one_bar_restores_the_other_bars_actions() {
        val holder = ScreenActionsHolder()
        val first = Any()
        val second = Any()
        val firstActions: @Composable RowScope.() -> Unit = {}
        val secondActions: @Composable RowScope.() -> Unit = {}
        val exitSelection: () -> Unit = {}

        holder.publishActions(
            first,
            actions = firstActions,
            selectionTitle = "1 selected",
            onExitSelection = exitSelection,
        )
        holder.publishActions(second, actions = secondActions, selectionTitle = null, onExitSelection = null)

        holder.withdrawActions(second)

        assertSame(firstActions, holder.actions, "the second bar must not null the first bar's actions")
        assertEquals("1 selected", holder.selectionTitle)
        assertSame(exitSelection, holder.onExitSelection, "the first bar's exit-selection handler must come back")

        holder.withdrawActions(first)

        assertNull(holder.actions)
        assertNull(holder.selectionTitle)
        assertNull(holder.onExitSelection)
    }

    @Test
    fun republishing_makes_a_publisher_effective_again() {
        val holder = ScreenActionsHolder()
        val first = Any()
        val second = Any()
        val firstTitle: @Composable () -> Unit = {}
        val secondTitle: @Composable () -> Unit = {}

        holder.publishBar(first, title = firstTitle, navigationIcon = null)
        holder.publishBar(second, title = secondTitle, navigationIcon = null)

        holder.publishBar(first, title = firstTitle, navigationIcon = null)

        assertSame(firstTitle, holder.title, "the most recent publication must be the effective one")
    }

    @Test
    fun clearing_the_holder_drops_every_publication() {
        val holder = ScreenActionsHolder()
        val owner = Any()

        holder.publishActions(owner, actions = {}, selectionTitle = "1 selected", onExitSelection = {})
        holder.publishBar(owner, title = {}, navigationIcon = null)

        holder.clear()

        assertNull(holder.actions)
        assertNull(holder.selectionTitle)
        assertNull(holder.onExitSelection)
        assertNull(holder.title)
        assertNull(holder.navigationIcon)
        assertFalse(holder.override)
    }
}
