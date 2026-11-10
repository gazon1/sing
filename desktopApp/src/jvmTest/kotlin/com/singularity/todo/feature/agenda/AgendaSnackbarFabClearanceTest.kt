@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.agenda

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.FabFootprint
import com.singularity.todo.core.ui.components.LocalFabBottomSpace
import com.singularity.todo.core.ui.components.TaggedSnackbarHost
import com.singularity.todo.test.helpers.mainRootBounds
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The undo snackbar keeps its action clear of the shell's floating action
 * button (#2752).
 *
 * The shell owns the button and publishes the space it takes; the snackbar
 * host keeps that space clear at the bottom, so a swipe-delete's Undo action
 * stays visible and tappable instead of hiding under the button.
 *
 * The layout here is the real one: the outer `Scaffold` is the shell's (it
 * places the button 16 dp above the bottom edge and measures it), the inner
 * `Scaffold` is a screen's (it hosts the snackbar at its bottom), exactly as
 * the agenda screen is built.
 */
@Tag("fast")
class AgendaSnackbarFabClearanceTest {
    @Test
    fun `the undo action stays clear of the button`() =
        runIsolatedComposeTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            setContent { ShellWithSnackbar(showButton = true) }

            waitForIdle()

            val action = unclippedBoundsOfTag(TestTags.SNACKBAR_ACTION)
            val button = unclippedBoundsOfTag(TestTags.TASKS_FAB)
            assertTrue(
                action.bottom <= button.top,
                "the undo action ends at ${action.bottom}, the button starts at ${button.top}",
            )
        }

    @Test
    fun `the snackbar keeps no blank space when no button is shown`() =
        runIsolatedComposeTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            setContent { ShellWithSnackbar(showButton = false) }

            waitForIdle()

            val action = unclippedBoundsOfTag(TestTags.SNACKBAR_ACTION)
            val root = mainRootBounds()
            // Nothing is on screen to make room for: the snackbar sits on the
            // bottom edge, as it did before the button was ever measured.
            //
            // The bound is the button's own size, not Material3's snackbar
            // metrics: the gap between the action and the bottom edge is
            // whatever Material3 leaves around its action inside the container,
            // and that container's height is Material3's to change. What is
            // under test is that no *button-sized* space was reserved — a
            // clearance hard-coded to any real button would leave more than
            // this here.
            val gap = root.bottom - action.bottom
            assertTrue(
                gap >= 0.dp && gap < BUTTON_SIZE,
                "the snackbar kept $gap below it with no button on screen",
            )
        }

    /**
     * The shell's `Scaffold` with the button over a screen's `Scaffold` that
     * hosts the snackbar — the two layers the agenda screen is built from.
     */
    @Composable
    private fun ShellWithSnackbar(showButton: Boolean) {
        val footprint = remember { FabFootprint() }
        val hostState = remember { SnackbarHostState() }
        LaunchedEffect(hostState) {
            hostState.showSnackbar(
                message = "\"Buy milk\" deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Indefinite,
            )
        }

        CompositionLocalProvider(
            LocalFabBottomSpace provides
                if (showButton) footprint.bottomSpace() else 0.dp,
        ) {
            Scaffold(
                floatingActionButton = {
                    if (!showButton) return@Scaffold
                    FloatingActionButton(
                        onClick = {},
                        modifier =
                            Modifier
                                .testTag(TestTags.TASKS_FAB)
                                .onSizeChanged { footprint.onMeasured(it.height) },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add task")
                    }
                },
                floatingActionButtonPosition = FabPosition.End,
            ) { padding ->
                Scaffold(
                    modifier = Modifier.padding(padding),
                    snackbarHost = { TaggedSnackbarHost(hostState) },
                ) { }
            }
        }
    }
}

/** A phone's width — the width the button and the snackbar collide at. */
private const val PHONE_WIDTH = 360

private const val PHONE_HEIGHT = 640

/**
 * Material3's medium floating action button — the smallest button the shell
 * draws, and the bound for "space was reserved for a button".
 */
private val BUTTON_SIZE = 56.dp
