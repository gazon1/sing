@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.agenda

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.onNodeWithTag
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.agenda.domain.model.AgendaUiState
import com.singularity.todo.feature.agenda.domain.model.RenderedSection
import com.singularity.todo.feature.agenda.presentation.screen.AgendaContent
import com.singularity.todo.feature.agenda.presentation.screen.AgendaHorizonNote
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Upcoming tab tells the user where a task dated beyond next week is seen.
 *
 * The three bottom-bar tabs cover a horizon (ADR
 * `2026-11-05-the-agenda-tabs-cover-a-horizon`), so a task due in three weeks is in
 * none of them. Without a line saying so, the only reading a user has is that the task
 * is gone — the decision was written down for developers, and this is the half a user
 * gets. The pointer names the Calendar and the Plans timeline, the two surfaces that
 * do hold the task, and it sits at the end of the tab: as the last item of the list, or
 * at the bottom of the empty state.
 */
@Tag("fast")
class AgendaHorizonNoteTest {
    private val today = LocalDate(2026, 10, 14)

    @Test
    fun `the pointer belongs to the Upcoming tab alone`() {
        assertEquals(
            AgendaHorizonNote.TEXT,
            AgendaHorizonNote.forDefinition(AgendaPresets.Upcoming),
            "the Upcoming tab is the one whose promise stops at next week, so it is " +
                "the tab that has to say where a later task is seen",
        )

        listOf(
            "Inbox" to AgendaPresets.Inbox,
            "Today" to AgendaPresets.Today,
            "the wide All view" to AgendaPresets.All,
        ).forEach { (label, definition) ->
            assertNull(
                AgendaHorizonNote.forDefinition(definition),
                "$label has no horizon, and a saved view or a project list is not a " +
                    "tab — none of them may point anywhere",
            )
        }
    }

    @Test
    fun `an empty Upcoming tab still says where a later task is seen`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<AgendaIntent>()
            setContent {
                AgendaContent(
                    state =
                        AgendaUiState.Loaded(
                            sections = persistentListOf(),
                            today = today,
                        ),
                    title = "Upcoming",
                    horizonNote = AgendaHorizonNote.forDefinition(AgendaPresets.Upcoming),
                    onIntent = { intents += it },
                )
            }

            // A tab with nothing in it is exactly where the user wonders what is
            // missing from it, so the pointer must survive the empty state.
            assertTextDisplayed(AgendaHorizonNote.TEXT)

            assertEquals(
                emptyList<AgendaIntent>(),
                intents,
                "the pointer informs — showing it must not perform an action",
            )
        }

    @Test
    fun `the pointer is shown on a tab that has sections`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<AgendaIntent>()
            setContent {
                AgendaContent(
                    state =
                        AgendaUiState.Loaded(
                            sections =
                                persistentListOf(
                                    RenderedSection(
                                        id = "next_week",
                                        name = "Next Week",
                                        tasks = persistentListOf(),
                                        badge = null,
                                    ),
                                ),
                            today = today,
                        ),
                    title = "Upcoming",
                    horizonNote = AgendaHorizonNote.forDefinition(AgendaPresets.Upcoming),
                    onIntent = { intents += it },
                )
            }

            assertTextDisplayed("Next Week")
            assertTextDisplayed(AgendaHorizonNote.TEXT)
        }

    @Test
    fun `a tab with no horizon shows no pointer`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<AgendaIntent>()
            setContent {
                AgendaContent(
                    state =
                        AgendaUiState.Loaded(
                            sections = persistentListOf(),
                            today = today,
                        ),
                    title = "Today",
                    horizonNote = AgendaHorizonNote.forDefinition(AgendaPresets.Today),
                    onIntent = { intents += it },
                )
            }

            onNodeWithTag(TestTags.AGENDA_HORIZON_NOTE, useUnmergedTree = true)
                .assertDoesNotExist()
        }
}
