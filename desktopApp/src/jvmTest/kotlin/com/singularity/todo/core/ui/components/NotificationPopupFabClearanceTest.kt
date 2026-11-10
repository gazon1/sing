@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.core.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The popup toast does not reserve room for the shell's button (#2752).
 *
 * The shell's button is drawn in the app's own window; the undo toast that
 * `NotificationHost` shows is drawn in a `Popup` — its own window, above the
 * button. The toast never needed the button's clearance, and taking it lifts
 * the toast off the bottom edge of the screen for nothing.
 *
 * The clearance is the shell's published value, so the test publishes a big
 * one: the toast must ignore it, and stay where it was before the snackbar
 * host learned about the button.
 */
@Tag("fast")
class NotificationPopupFabClearanceTest {
    @Test
    fun `the popup toast keeps no clearance for a button it does not share a window with`() =
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

            val action =
                onAllNodesWithTag(TestTags.SNACKBAR_ACTION)
                    .fetchSemanticsNodes()
                    .firstOrNull()
            assertTrue(action != null, "the popup toast never showed its action")

            // The popup is its own window: the toast's own root is that window,
            // and the window's bottom is where the toast is anchored. What is
            // measured is the space the toast leaves below itself inside its own
            // window — the clearance, if the host took one.
            //
            // The window is the last root of the scene: the app's own window is
            // the first one, and the popup opens its root after it.
            val roots = onAllNodes(isRoot(), useUnmergedTree = true)
            val rootCount = roots.fetchSemanticsNodes().size
            assertTrue(rootCount >= 2, "the toast did not open its own window: $rootCount root(s)")
            val popupWindow = roots[rootCount - 1].getUnclippedBoundsInRoot()
            val toast = onAllNodesWithTag(TestTags.SNACKBAR_ACTION)[0].getUnclippedBoundsInRoot()
            val gap = popupWindow.bottom - toast.bottom
            assertTrue(
                gap >= 0.dp && gap < BUTTON_CLEARANCE,
                "the popup toast kept $gap below itself for a button in another window " +
                    "(window $popupWindow, toast $toast)",
            )
        }
}

/** A phone's width — the width the button and the toast collide at. */
private const val PHONE_WIDTH = 360

private const val PHONE_HEIGHT = 640

/**
 * The space the shell's button takes: the button's own height plus the gap
 * Material3 leaves under it. Published by the shell, ignored by the popup.
 */
private val BUTTON_CLEARANCE = 72.dp
