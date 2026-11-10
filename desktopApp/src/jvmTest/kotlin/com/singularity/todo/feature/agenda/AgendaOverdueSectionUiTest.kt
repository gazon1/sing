@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.agenda

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.agenda.domain.model.AgendaRowItem
import com.singularity.todo.feature.agenda.domain.model.RenderedSection
import com.singularity.todo.feature.agenda.presentation.screen.AgendaContent
import com.singularity.todo.feature.agenda.presentation.state.AgendaUiState
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.testTask
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * UI behavior for a large overdue section on the Today agenda: collapsed by state,
 * count/controls visible, tasks hidden until expanded, and full Overdue reachable.
 */
@Tag("fast")
class AgendaOverdueSectionUiTest {
    private val today = LocalDate(2026, 10, 14)

    private fun overdueSection(count: Int): RenderedSection {
        val tasks =
            (1..count).map { i ->
                AgendaRowItem(
                    task =
                        testTask(
                            id = TaskId("o$i"),
                            title = "Overdue $i",
                            dueDate = today.minus(i.coerceAtMost(10), DateTimeUnit.DAY),
                        ),
                )
            }
        return RenderedSection(
            id = "overdue",
            name = "Overdue",
            tasks = tasks.toImmutableList(),
            badge = count,
        )
    }

    @Test
    fun `collapsed overdue section keeps count and today reachable without showing overdue rows`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<AgendaIntent>()
            val overdue = overdueSection(1500)
            val todaySection =
                RenderedSection(
                    id = "today",
                    name = "Today",
                    tasks =
                        persistentListOf(
                            AgendaRowItem(
                                task = testTask(id = TaskId("t1"), title = "Do today", dueDate = today),
                            ),
                        ),
                )
            setContent {
                AgendaContent(
                    state =
                        AgendaUiState.Loaded(
                            sections = persistentListOf(overdue, todaySection),
                            today = today,
                            isTodayAgenda = true,
                            collapsedSectionIds = persistentSetOf("overdue"),
                        ),
                    title = "Today",
                    onIntent = { intents += it },
                )
            }

            onNodeWithTag(
                TestTags.agendaSection("Overdue"),
            ).assertIsDisplayed().assertTextContains("1500", substring = true)
            onNodeWithTag(TestTags.agendaSectionToggle("Overdue")).assertIsDisplayed()
            onNodeWithTag(TestTags.agendaSectionViewAll("Overdue")).assertIsDisplayed()
            onNodeWithTag(TestTags.agendaSection("Today")).assertIsDisplayed()
            onNodeWithTag(TestTags.taskItem("Do today")).assertIsDisplayed()
            assertTrue(
                runCatching { onNodeWithTag(TestTags.taskItem("Overdue 1")).assertIsDisplayed() }.isFailure,
                "collapsed overdue rows must not be shown",
            )
        }

    @Test
    fun `expanded overdue section shows rows`() =
        runIsolatedComposeTest {
            val overdue = overdueSection(2)
            setContent {
                AgendaContent(
                    state =
                        AgendaUiState.Loaded(
                            sections = persistentListOf(overdue),
                            today = today,
                            isTodayAgenda = true,
                            collapsedSectionIds = persistentSetOf(),
                        ),
                    title = "Today",
                    onIntent = {},
                )
            }

            onNodeWithTag(TestTags.taskItem("Overdue 1")).assertIsDisplayed()
            onNodeWithTag(TestTags.taskItem("Overdue 2")).assertIsDisplayed()
        }

    @Test
    fun `toggle and view all emit intents`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<AgendaIntent>()
            val overdue = overdueSection(1500)
            setContent {
                AgendaContent(
                    state =
                        AgendaUiState.Loaded(
                            sections = persistentListOf(overdue),
                            today = today,
                            isTodayAgenda = true,
                            collapsedSectionIds = persistentSetOf("overdue"),
                        ),
                    title = "Today",
                    onIntent = { intents += it },
                )
            }

            onNodeWithTag(TestTags.agendaSectionToggle("Overdue")).performClick()
            onNodeWithTag(TestTags.agendaSectionViewAll("Overdue")).performClick()

            assertTrue(intents.any { it == AgendaIntent.ToggleSection("overdue") })
            assertTrue(intents.any { it == AgendaIntent.OpenOverdue })
        }

    @Test
    fun `small overdue section on Today stays expanded without extra controls when state is expanded`() =
        runIsolatedComposeTest {
            val overdue = overdueSection(2)
            setContent {
                AgendaContent(
                    state =
                        AgendaUiState.Loaded(
                            sections = persistentListOf(overdue),
                            today = today,
                            isTodayAgenda = true,
                            collapsedSectionIds = persistentSetOf(),
                        ),
                    title = "Today",
                    onIntent = {},
                )
            }

            onNodeWithTag(
                TestTags.agendaSection("Overdue"),
            ).assertIsDisplayed().assertTextContains("2", substring = true)
            onNodeWithTag(TestTags.agendaSectionToggle("Overdue")).assertIsDisplayed()
            onNodeWithTag(TestTags.agendaSectionViewAll("Overdue")).assertIsDisplayed()
            onNodeWithTag(TestTags.taskItem("Overdue 1")).assertIsDisplayed()
        }
}
