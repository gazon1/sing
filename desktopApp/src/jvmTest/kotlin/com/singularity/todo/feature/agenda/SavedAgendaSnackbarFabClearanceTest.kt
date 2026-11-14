@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.agenda

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.LocalFabBottomSpace
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import com.singularity.todo.test.helpers.unclippedBoundsOfText
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A screen that owns both its button and its snackbar needs no shell clearance
 * (#2814).
 *
 * `LocalFabBottomSpace` is the **shell's** button's clearance. A screen whose
 * FAB and snackbar live in the same Material3 `Scaffold` — `SavedAgendaListScreen`
 * — keeps that `Scaffold`'s native `SnackbarHost`: Material3 already places the
 * snackbar clear of the FAB slot, and taking the shell's value as well would
 * lift it a second time.
 *
 * Both halves are asserted. The snackbar's action clears the screen's own
 * button, and an unrelated non-zero clearance published by an outer shell-like
 * parent does not push it up. The second test is the one that fails when this
 * screen is switched to `TaggedSnackbarHost`: the action would rise by the
 * shell's footprint.
 */
@Tag("fast")
class SavedAgendaSnackbarFabClearanceTest {
    @Test
    fun `the screen's snackbar action clears its own button`() =
        runIsolatedComposeTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            setContent { ScreenOwnedScaffold(shellClearance = 0.dp) }

            waitForIdle()

            val action = unclippedBoundsOfText(ACTION_LABEL)
            val button = unclippedBoundsOfTag(TestTags.SAVED_AGENDA_CREATE_FAB)
            assertTrue(
                action.bottom <= button.top,
                "the snackbar action ends at ${action.bottom}, the screen's button starts at ${button.top}",
            )
        }

    @Test
    fun `an unrelated shell clearance does not lift the screen's snackbar`() =
        runIsolatedComposeTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            setContent { ScreenOwnedScaffold(shellClearance = SHELL_CLEARANCE) }

            waitForIdle()

            val action = unclippedBoundsOfText(ACTION_LABEL)
            val button = unclippedBoundsOfTag(TestTags.SAVED_AGENDA_CREATE_FAB)
            assertTrue(
                action.bottom <= button.top,
                "the snackbar action ends at ${action.bottom}, the screen's button starts at ${button.top}",
            )

            // The screen did not consume the shell's clearance. Had it, the
            // snackbar would sit the published footprint *further* above its own
            // button. The bound is the published value, not Material3's own
            // snackbar-to-FAB spacing — that is Material3's to change, and only
            // the extra lift is what is under test.
            val gap = button.top - action.bottom
            assertTrue(
                gap < SHELL_CLEARANCE,
                "the snackbar kept $gap above its button — it took a clearance for a button it does not own",
            )
        }

    /**
     * The shape `SavedAgendaListScreen` is built from: one `Scaffold` holding
     * both the screen's FAB and its snackbar. [shellClearance] is what an outer
     * shell would publish, which this screen must not consume.
     */
    @Composable
    private fun ScreenOwnedScaffold(shellClearance: Dp) {
        val hostState = remember { SnackbarHostState() }
        LaunchedEffect(hostState) {
            hostState.showSnackbar(
                message = "\"Weekly review\" copied",
                actionLabel = ACTION_LABEL,
                duration = SnackbarDuration.Indefinite,
            )
        }

        CompositionLocalProvider(LocalFabBottomSpace provides shellClearance) {
            Scaffold(
                floatingActionButton = {
                    FloatingActionButton(
                        onClick = {},
                        modifier = Modifier.testTag(TestTags.SAVED_AGENDA_CREATE_FAB),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Create view")
                    }
                },
                floatingActionButtonPosition = FabPosition.End,
                snackbarHost = { SnackbarHost(hostState) },
            ) { }
        }
    }
}

/** A phone's width — the width the button and the snackbar collide at. */
private const val PHONE_WIDTH = 360

private const val PHONE_HEIGHT = 640

/**
 * The snackbar's action label. Material3's own host has no testTag for it, so
 * the action is found by its text — which is also why the issue asks for an
 * action: the action area is part of the snackbar's footprint.
 */
private const val ACTION_LABEL = "Undo"

/** What the shell publishes when its own button is on screen. */
private val SHELL_CLEARANCE = 72.dp
