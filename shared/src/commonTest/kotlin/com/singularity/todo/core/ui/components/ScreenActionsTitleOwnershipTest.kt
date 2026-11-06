package com.singularity.todo.core.ui.components

import androidx.compose.runtime.Composable
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The title slot's ownership contract.
 *
 * The shell used to clear the slot from an effect keyed on the top-level route.
 * That effect fires after the new screen has composed, so a screen that owned
 * its bar title could have it cleared immediately after publishing it, and only
 * a later recomposition put it back. Clearing is now the publishing screen's
 * own departure, and only for the entry that screen owns.
 */
@Tag("fast")
class ScreenActionsTitleOwnershipTest {
    @Test
    fun `a screen keeps the title it published until it leaves`() {
        val holder = ScreenActionsHolder()
        val calendar = Any()

        holder.publishTitle(calendar, { }, null)

        assertTrue(holder.override, "a published title replaces the shell's own")
        assertNotNull(holder.title, "the screen that published must keep its title")

        holder.releaseTitle(calendar)

        assertFalse(holder.override, "leaving releases the override")
        assertNull(holder.title, "leaving takes the title down with it")
        assertNull(holder.navigationIcon, "leaving takes the navigation icon down with it")
    }

    @Test
    fun `a departed screen does not take away the title of the screen that replaced it`() {
        val holder = ScreenActionsHolder()
        val calendar = Any()
        val agenda = Any()
        val agendaTitle: @Composable () -> Unit = { }

        holder.publishTitle(calendar, { }, null)
        holder.publishTitle(agenda, agendaTitle, null)

        // The screen the user has left disposes after the screen on screen has
        // published — the exact ordering the old route-keyed clear lost.
        holder.releaseTitle(calendar)

        assertTrue(
            holder.override,
            "a late disposal of the previous screen must not clear the title on screen",
        )
        assertSame(
            agendaTitle,
            holder.title,
            "the screen on screen keeps the title it published",
        )
    }

    @Test
    fun `a nested screen leaving restores the title underneath it`() {
        val holder = ScreenActionsHolder()
        val agenda = Any()
        val calendar = Any()
        val agendaTitle: @Composable () -> Unit = { }

        holder.publishTitle(agenda, agendaTitle, null)
        holder.publishTitle(calendar, { }, null)

        // The nested screen is popped. The screen underneath never recomposed,
        // so only the holder can bring its title back.
        holder.releaseTitle(calendar)

        assertTrue(holder.override)
        assertSame(agendaTitle, holder.title)
    }

    @Test
    fun `a holder with no publisher leaves the shell's own title in place`() {
        val holder = ScreenActionsHolder()

        assertFalse(holder.override)
        assertNull(holder.title)
    }
}
