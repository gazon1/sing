@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.agenda

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.agenda.domain.model.AgendaUiState
import com.singularity.todo.feature.agenda.domain.model.RenderedSection
import com.singularity.todo.feature.agenda.presentation.screen.AgendaContent
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * The agenda's bar and the section headers under it.
 *
 * Two things are pinned here that the domain tests cannot see: the title the bar
 * draws, with the greeting line still underneath it (#1954), and the list keys —
 * the working day's morning and afternoon blocks are both called "Work", and a
 * list that keys a header by its display name takes the whole screen down with
 * "Key ... was already used" the moment both blocks hold something.
 */
@Tag("fast")
class AgendaHeaderTest {
    @Test
    fun `the bar shows the title and the greeting underneath it`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<AgendaIntent>()
            setContent {
                AgendaContent(
                    state = AgendaUiState.Error(message = "Failed to load agenda"),
                    title = "Today",
                    greeting = "Good morning",
                    onIntent = { intents += it },
                )
            }

            assertTextDisplayed("Today")
            assertTextDisplayed("Good morning")
        }

    @Test
    fun `two sections with the same display name render two headers`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<AgendaIntent>()
            setContent {
                AgendaContent(
                    state =
                        AgendaUiState.Loaded(
                            sections =
                                persistentListOf(
                                    RenderedSection(
                                        id = "work_morning",
                                        name = "Work",
                                        tasks = persistentListOf(),
                                    ),
                                    RenderedSection(
                                        id = "work_afternoon",
                                        name = "Work",
                                        tasks = persistentListOf(),
                                    ),
                                ),
                            today = LocalDate(2026, 10, 14),
                        ),
                    title = "Today",
                    onIntent = { intents += it },
                )
            }

            onAllNodesWithText("Work").assertCountEquals(2)
        }
}
