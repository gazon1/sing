@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.agenda

import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.agenda.domain.model.AgendaUiState
import com.singularity.todo.feature.agenda.presentation.screen.AgendaContent
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The agenda's error state, driven the way a user drives it.
 *
 * #1386 gave the failed load a way out: the error branch renders a Retry button
 * that dispatches [AgendaIntent.Retry], and the ViewModel's trigger re-runs the
 * collection. The ViewModel half is pinned in `AgendaViewModelTest` — a retry
 * reloads, and a retry that fails again lands back in the error state. What
 * these tests pin is the half a user actually touches: that the screen *offers*
 * the button on the error state, and that pressing it asks for the retry.
 *
 * A button that renders but is wired to nothing, or an error state that shows
 * the message with no way out, would pass every VM test and still strand the
 * user on the spinner. That is the regression these two tests exist for.
 */
@Tag("fast")
class AgendaErrorRetryTest {
    @Test
    fun `the error state shows the failure and offers a retry`() =
        runIsolatedComposeTest {
            // A reference instead of an empty lambda: this repo's
            // NoEmptyOnClickLambda rule rejects `onIntent = {}` outside previews,
            // and detekt scans this source set.
            val intents = mutableListOf<AgendaIntent>()
            setContent {
                AgendaContent(
                    state = AgendaUiState.Error(message = "Failed to load agenda"),
                    title = "Agenda",
                    onIntent = { intents += it },
                )
            }

            // The message first: it is what makes this assertion about the error
            // branch rather than about a Retry button that some other state grew.
            assertTextDisplayed("Failed to load agenda")
            assertTextDisplayed("Retry")

            assertTrue(
                intents.isEmpty(),
                "showing the error state must not ask for a retry by itself — the button is the user's move",
            )
        }

    @Test
    fun `tapping retry dispatches the retry intent`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<AgendaIntent>()
            setContent {
                AgendaContent(
                    state = AgendaUiState.Error(message = "Failed to load agenda"),
                    title = "Agenda",
                    onIntent = { intents += it },
                )
            }

            clickText("Retry")

            assertEquals(
                listOf<AgendaIntent>(AgendaIntent.Retry),
                intents,
                "the Retry button must ask the screen for a retry — a button wired to nothing strands the user",
            )
        }
}
