package com.singularity.todo.core.ui.components

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shell bar's title precedence, on the deciding side.
 *
 * The bar has one title and three candidates: the selection count, a title a
 * screen published, and the destination's name. The Android shell draws
 * whatever [resolveShellBarTitleSource] answers and holds no copy of the rule,
 * so these four cases are the whole rule — including the two where the
 * destination's name still wins.
 */
@Tag("fast")
class ShellBarTitleSourceTest {
    @Test
    fun `a selection title wins over everything`() {
        assertEquals(
            ShellBarTitleSource.Selection("2 selected"),
            resolveShellBarTitleSource(
                selectionTitle = "2 selected",
                overrideShell = true,
                hasPublishedTitle = true,
                destinationTitle = "Tasks",
            ),
        )
    }

    @Test
    fun `an override with a published title wins over the destination`() {
        assertEquals(
            ShellBarTitleSource.Published,
            resolveShellBarTitleSource(
                selectionTitle = null,
                overrideShell = true,
                hasPublishedTitle = true,
                destinationTitle = "Tasks",
            ),
        )
    }

    @Test
    fun `a published title without the override leaves the destination name`() {
        assertEquals(
            ShellBarTitleSource.Destination("Tasks"),
            resolveShellBarTitleSource(
                selectionTitle = null,
                overrideShell = false,
                hasPublishedTitle = true,
                destinationTitle = "Tasks",
            ),
        )
    }

    @Test
    fun `an override without a published title leaves the destination name`() {
        assertEquals(
            ShellBarTitleSource.Destination("Tasks"),
            resolveShellBarTitleSource(
                selectionTitle = null,
                overrideShell = true,
                hasPublishedTitle = false,
                destinationTitle = "Tasks",
            ),
        )
    }
}
