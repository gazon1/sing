@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.agenda

import androidx.compose.runtime.CompositionLocalProvider
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.LocalScreenActions
import com.singularity.todo.core.ui.components.ScreenActionsHolder
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.agenda.presentation.screen.AgendaContent
import com.singularity.todo.feature.agenda.presentation.state.AgendaUiState
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The greeting is drawn where both clients actually show it.
 *
 * It used to be a second line in the shared bar's title slot. On Android that wrapper
 * draws no bar: it hands the screen's actions to the shell and publishes the title slot
 * only when the caller asks for the shell to be overridden, which the agenda never did.
 * The greeting was therefore never seen on Android while the desktop client showed it —
 * two clients disagreeing about a value neither was testing.
 *
 * These two tests pin the halves that matter: the greeting is in the body, and it is
 * still there when the Android shell is present and the bar contributes no title. The
 * second one is the Android path — the shell-present condition is exactly what makes
 * the bar drop the title slot — and it is why the desktop rendering alone is not enough.
 */
@Tag("fast")
class AgendaGreetingRenderTest {
    private val loaded =
        AgendaUiState.Loaded(
            sections = persistentListOf(),
            today = LocalDate(2026, 9, 16),
            greeting = "Good afternoon",
        )

    @Test
    fun `the greeting is rendered in the agenda body`() =
        runIsolatedComposeTest {
            // A reference rather than an empty lambda: NoEmptyOnClickLambda rejects
            // `onIntent = {}` outside previews, and detekt scans this source set.
            val intents = mutableListOf<AgendaIntent>()
            setContent {
                AgendaContent(
                    state = loaded,
                    title = "Today",
                    greeting = loaded.greeting,
                    onIntent = { intents += it },
                )
            }

            assertTextDisplayed("Good afternoon")
            assertTagDisplayed(TestTags.AGENDA_GREETING)
            assertTrue(intents.isEmpty(), "drawing the greeting must not dispatch an intent")
        }

    /**
     * The Android condition: the shell owns the only bar, so the shared wrapper
     * publishes nothing to it and the greeting has nowhere to live but the body.
     */
    @Test
    fun `the greeting survives the shell owning the only bar`() =
        runIsolatedComposeTest {
            val holder = ScreenActionsHolder()
            val intents = mutableListOf<AgendaIntent>()
            setContent {
                CompositionLocalProvider(LocalScreenActions provides holder) {
                    AgendaContent(
                        state = loaded,
                        title = "Today",
                        greeting = loaded.greeting,
                        onIntent = { intents += it },
                    )
                }
            }
            waitForIdle()

            assertNull(
                holder.title,
                "the agenda must not publish its title slot to the shell — " +
                    "that is the slot the greeting used to be lost in",
            )
            assertTextDisplayed("Good afternoon")
            assertTagDisplayed(TestTags.AGENDA_GREETING)
        }
}
