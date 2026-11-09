package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

@Tag("fast")
class ScreenActionsHolderTest {
    @Test
    fun actions_default_to_null() {
        assertNull(ScreenActionsHolder().actions)
    }

    /**
     * The row belongs to the screen on screen, not to whichever bar recomposed last.
     *
     * A pushed screen and the screen it was pushed from are composed at the same
     * time, and the one underneath recomposes too. With a single unowned slot that
     * background recomposition replaced the row of the screen the user was looking
     * at — the task detail's overflow and the note editor's save reached the bar
     * as nothing at all.
     */
    @Test
    fun the_action_row_belongs_to_the_screen_on_screen() {
        val holder = ScreenActionsHolder()
        val under: @Composable RowScope.() -> Unit = {}
        val onScreen: @Composable RowScope.() -> Unit = {}
        val underPublisher = Any()
        val onScreenPublisher = Any()

        holder.publishActions(underPublisher, under)
        holder.publishActions(onScreenPublisher, onScreen)
        assertSame(onScreen, holder.actions)

        // The screen underneath republishes: its entry is replaced in place.
        holder.publishActions(underPublisher, under)
        assertSame(onScreen, holder.actions)

        // Leaving the screen on screen reveals the row underneath it.
        holder.releaseActions(onScreenPublisher)
        assertSame(under, holder.actions)
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
}
