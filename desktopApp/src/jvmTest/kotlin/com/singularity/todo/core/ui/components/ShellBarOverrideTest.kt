@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shell bar override, on the publishing side.
 *
 * On Android the shell owns the only bar, so a screen that draws its own header
 * publishes that header to the shell instead of drawing a second bar. The
 * publishing side is [TopAppBar] with `overrideShell = true`, [ShellOverride],
 * and the action slots every bar publishes; the contract they keep with the
 * shell is [ScreenActionsHolder].
 *
 * Nothing asserted that contract before this test. The only test that touched
 * the holder checked that its fields start empty — which is a statement about
 * the constructor, not about the publication. So a screen could publish into a
 * holder that had no such field, the shell would draw nothing, and neither the
 * compiler nor any test would have said so: that is how #1698 left the Android
 * bar with no title at all.
 *
 * The tests mount the publishers alone, with a holder provided through
 * [LocalScreenActions], and assert both halves of the contract: what a
 * publisher publishes while it is there, and what it takes back when it leaves.
 * The multi-publisher tests pin the ownership rule: a publisher that leaves
 * takes down only its own publication, and the last one to leave clears the
 * slot.
 */
@Tag("fast")
class ShellBarOverrideTest {
    @Test
    fun `the bar publishes its own header and takes the override down when it leaves`() =
        runIsolatedComposeTest {
            val holder = ScreenActionsHolder()
            val barVisible = mutableStateOf(true)

            setContent {
                if (barVisible.value) {
                    CompositionLocalProvider(LocalScreenActions provides holder) {
                        TopAppBar(
                            title = { Text("Calendar") },
                            navigationIcon = { Text("Back") },
                            overrideShell = true,
                        )
                    }
                }
            }
            waitForIdle()

            assertTrue(
                holder.override,
                "a bar that takes the shell over must say so, or the shell draws its own title",
            )
            assertNotNull(
                holder.title,
                "the published title must reach the shell, or the bar shows nothing",
            )
            assertNotNull(
                holder.navigationIcon,
                "the published navigation icon must reach the shell, or the shell keeps its back button",
            )

            // The screen leaves: the bar goes out of the composition, and the
            // override it published must go with it — by the bar that published
            // it, and by nothing else.
            barVisible.value = false
            waitForIdle()

            assertFalse(
                holder.override,
                "the bar that published the override must take it down when it leaves",
            )
            assertNull(
                holder.title,
                "the published title must be taken down with the override",
            )
            assertNull(
                holder.navigationIcon,
                "the published navigation icon must be taken down with the override",
            )
        }

    @Test
    fun `turning the override off takes the bar down and keeps the actions`() =
        runIsolatedComposeTest {
            val holder = ScreenActionsHolder()
            val overrideShell = mutableStateOf(true)

            setContent {
                CompositionLocalProvider(LocalScreenActions provides holder) {
                    TopAppBar(
                        title = { Text("Settings") },
                        navigationIcon = { Text("Back") },
                        actions = { Text("action") },
                        overrideShell = overrideShell.value,
                    )
                }
            }
            waitForIdle()

            assertTrue(holder.override)
            assertNotNull(holder.navigationIcon)

            overrideShell.value = false
            waitForIdle()

            assertFalse(holder.override, "turning the override off must take the bar down")
            assertNull(holder.title, "the title must go with the override")
            assertNull(holder.navigationIcon, "the navigation icon must go with the override")
            assertNotNull(holder.actions, "the bar is still composed, so its actions stay published")
        }

    @Test
    fun `a shell override publishes its header and takes it down when it leaves`() =
        runIsolatedComposeTest {
            val holder = ScreenActionsHolder()
            val overrideVisible = mutableStateOf(true)

            setContent {
                if (overrideVisible.value) {
                    CompositionLocalProvider(LocalScreenActions provides holder) {
                        ShellOverride(
                            title = { Text("Calendar") },
                            navigationIcon = { Text("Back") },
                        )
                    }
                }
            }
            waitForIdle()

            assertTrue(holder.override, "a shell override must take the bar over while it is composed")
            assertNotNull(holder.title, "the override's title must reach the shell")
            assertNotNull(holder.navigationIcon, "the override's navigation icon must reach the shell")

            overrideVisible.value = false
            waitForIdle()

            assertFalse(holder.override, "the override must be taken down when it leaves")
            assertNull(holder.title, "the override's title must be taken down when it leaves")
            assertNull(holder.navigationIcon, "the override's navigation icon must be taken down when it leaves")
        }

    @Test
    fun `a shell override that leaves leaves the top bar's header in place`() =
        runIsolatedComposeTest {
            val holder = ScreenActionsHolder()
            val overrideVisible = mutableStateOf(true)

            setContent {
                CompositionLocalProvider(LocalScreenActions provides holder) {
                    TopAppBar(
                        title = { Text("Settings") },
                        navigationIcon = { Text("Back") },
                        overrideShell = true,
                    )
                    if (overrideVisible.value) {
                        ShellOverride(title = { Text("Calendar") })
                    }
                    PublishedTitle(holder)
                }
            }
            waitForIdle()

            assertTextDisplayed("Calendar")
            assertTrue(holder.override)

            // The override leaves while the bar is still composed: the bar's
            // publication must become effective again instead of the slot
            // being cleared under it.
            overrideVisible.value = false
            waitForIdle()

            assertTextDisplayed("Settings")
            assertTrue(holder.override, "the bar that is still composed still owns the override")
            assertNotNull(holder.title, "the top bar's title must survive the override's disposal")
            assertNotNull(holder.navigationIcon, "the top bar's navigation icon must survive the override's disposal")
        }

    @Test
    fun `a top bar that leaves leaves the shell override's header in place`() =
        runIsolatedComposeTest {
            val holder = ScreenActionsHolder()
            val barVisible = mutableStateOf(true)
            val overrideVisible = mutableStateOf(true)

            setContent {
                CompositionLocalProvider(LocalScreenActions provides holder) {
                    if (overrideVisible.value) {
                        ShellOverride(
                            title = { Text("Calendar") },
                            navigationIcon = { Text("Back") },
                        )
                    }
                    if (barVisible.value) {
                        TopAppBar(
                            title = { Text("Settings") },
                            navigationIcon = { Text("Back") },
                            overrideShell = true,
                        )
                    }
                    PublishedTitle(holder)
                }
            }
            waitForIdle()

            assertTextDisplayed("Settings")

            barVisible.value = false
            waitForIdle()

            assertTextDisplayed("Calendar")
            assertTrue(holder.override, "the override that is still composed still owns the bar")
            assertNotNull(holder.title, "the override's title must survive the bar's disposal")
            assertNotNull(holder.navigationIcon, "the override's navigation icon must survive the bar's disposal")

            // The last publisher leaves: now the slot goes empty.
            overrideVisible.value = false
            waitForIdle()

            assertFalse(holder.override, "the last publisher to leave must clear the override")
            assertNull(holder.title, "the last publisher to leave must clear the title")
            assertNull(holder.navigationIcon, "the last publisher to leave must clear the navigation icon")
        }

    @Test
    fun `a second bar leaving leaves the first bar's actions in place`() =
        runIsolatedComposeTest {
            val holder = ScreenActionsHolder()
            val firstVisible = mutableStateOf(true)
            val secondVisible = mutableStateOf(true)

            setContent {
                CompositionLocalProvider(LocalScreenActions provides holder) {
                    if (firstVisible.value) {
                        TopAppBar(
                            actions = { Text("first") },
                            selectionTitle = "1 selected",
                            onExitSelection = {},
                        )
                    }
                    if (secondVisible.value) {
                        TopAppBar(
                            actions = { Text("second") },
                            selectionTitle = null,
                            onExitSelection = null,
                        )
                    }
                    PublishedActions(holder)
                }
            }
            waitForIdle()

            assertTextDisplayed("second")
            assertNull(holder.selectionTitle, "the newer bar published no selection state")

            secondVisible.value = false
            waitForIdle()

            assertTextDisplayed("first")
            assertNotNull(holder.actions, "the second bar must not null the first bar's actions")
            assertEquals("1 selected", holder.selectionTitle, "the first bar's selection state must come back")
            assertNotNull(holder.onExitSelection, "the first bar's exit-selection handler must come back")

            firstVisible.value = false
            waitForIdle()

            assertNull(holder.actions, "the last bar to leave must clear the actions")
            assertNull(holder.selectionTitle, "the last bar to leave must clear the selection title")
            assertNull(holder.onExitSelection, "the last bar to leave must clear the exit-selection handler")
        }
}

/** Renders the slot the shell would draw, so a test can tell whose publication is effective. */
@Composable
private fun PublishedTitle(holder: ScreenActionsHolder) {
    holder.title?.invoke()
}

/** Renders the actions the shell would draw, so a test can tell whose publication is effective. */
@Composable
private fun PublishedActions(holder: ScreenActionsHolder) {
    Row { holder.actions?.invoke(this) }
}
