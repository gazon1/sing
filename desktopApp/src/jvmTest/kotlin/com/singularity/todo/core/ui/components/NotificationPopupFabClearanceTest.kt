@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.core.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.countNodesWithTagInAnyRoot
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.semanticsRoots
import com.singularity.todo.test.helpers.spaceBelowTagInItsRoot
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The toast in a popup keeps the shell's button's clearance clear (#2752).
 *
 * A `Popup` is its own semantics root, drawn above the app's window — so the
 * toast covers the shell's button instead of the other way round, and the
 * button is hidden (and untappable) while the toast is up. The host therefore
 * takes the clearance the shell publishes, and the toast sits above the button.
 *
 * The clearance is the shell's published value, so the test publishes a big
 * one: the toast must leave that much clear below itself inside its own root.
 */
@Tag("fast")
class NotificationPopupFabClearanceTest {
    @Test
    fun `the popup toast reserves the button's clearance`() =
        runIsolatedComposeTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            setContent {
                CompositionLocalProvider(LocalFabBottomSpace provides BUTTON_CLEARANCE) {
                    NotificationHost(
                        events =
                            flowOf(
                                Notification.Undo(
                                    title = "\"Buy milk\" deleted",
                                    onAction = {},
                                ),
                            ),
                    )
                }
            }

            waitForIdle()

            // The toast really showed: the action is the whole point of the
            // undo, and a toast without it is a different screen.
            assertTrue(
                countNodesWithTagInAnyRoot(TestTags.SNACKBAR_ACTION) > 0,
                "the popup toast never showed its action",
            )

            // The toast lives in its own root — the popup's, not the app's.
            val rootCount = semanticsRoots().size
            assertTrue(rootCount >= 2, "the toast did not open its own root: $rootCount root(s)")

            // The space the toast leaves below itself inside its own root — the
            // clearance, if the host took one. Measured against the root the
            // toast is in, not the app's: a toast in a popup has to be measured
            // against the root it is drawn in, or the answer says nothing.
            val gap = spaceBelowTagInItsRoot(TestTags.SNACKBAR_ACTION)
            assertTrue(
                gap >= BUTTON_CLEARANCE,
                "the popup toast kept only $gap below itself for a button in the app's window",
            )
        }
}

/** A phone's width — the width the button and the toast collide at. */
private const val PHONE_WIDTH = 360

private const val PHONE_HEIGHT = 640

/**
 * The space the shell's button takes: the button's own height plus the gap
 * Material3 leaves under it. Published by the shell, taken by every host that
 * draws at the bottom of the screen.
 */
private val BUTTON_CLEARANCE = 72.dp
