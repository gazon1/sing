@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.test.fakes.testTask
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * FR-019: the task detail screen shows the dates of the series.
 *
 * Component test for [TaskSeriesCard]: it renders one row per series member with
 * the member's date, so a series is visible as a list of dated occurrences.
 */
@Tag("fast")
class TaskSeriesCardTest {

    @Test
    fun `a series card lists every member's date`() = runIsolatedComposeTest {
        val members = listOf(
            testTask(id = TaskId("t1"), title = "Buy milk", dueDate = LocalDate(2026, 10, 5)),
            testTask(id = TaskId("t2"), title = "Buy milk", dueDate = LocalDate(2026, 10, 12)),
        ).toImmutableList()

        setContent {
            TaskSeriesCard(
                members = members,
                onOpenMember = {},
                onApplyToAll = {},
                onDetach = {},
                onDeleteSeries = {},
            )
        }

        assertTextDisplayed("Серия · 2 дат")
    }
}