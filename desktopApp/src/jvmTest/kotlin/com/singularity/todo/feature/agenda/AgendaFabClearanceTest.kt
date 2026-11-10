@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.agenda

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.FabFootprint
import com.singularity.todo.core.ui.components.LocalFabBottomSpace
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.AgendaRowItem
import com.singularity.todo.feature.agenda.domain.model.RenderedSection
import com.singularity.todo.feature.agenda.presentation.screen.AgendaContent
import com.singularity.todo.feature.agenda.presentation.state.AgendaUiState
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.helpers.mainRootBounds
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The agenda list keeps its last row clear of the shell's floating action
 * button (#2743).
 *
 * The shell owns the button and publishes the space it takes; the list adds
 * that space to its bottom content padding. These are the three task lists the
 * agenda shows — Today, Upcoming and a project's tasks — at a phone's width,
 * where the button and the last row are close enough to collide.
 *
 * The button here is the shell's own: it is placed the way Material3's
 * `Scaffold` places it (16 dp above the bottom edge, at the end of the row)
 * and its height is measured, so the test measures the same footprint the
 * shell publishes.
 */
@Tag("fast")
class AgendaFabClearanceTest {
    @Test
    fun `the last row of today stays above the button`() = assertLastRowAboveFab(AgendaPresets.Today, "Today")

    @Test
    fun `the last row of upcoming stays above the button`() = assertLastRowAboveFab(AgendaPresets.Upcoming, "Upcoming")

    @Test
    fun `the last row of a project's tasks stays above the button`() =
        assertLastRowAboveFab(AgendaPresets.byProject(ProjectId("p1")), "Project")

    @Test
    fun `the list keeps no blank space when no button is shown`() =
        runIsolatedComposeTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            setContent {
                Box(Modifier.fillMaxSize()) {
                    AgendaContent(
                        state = agendaState(AgendaPresets.Today),
                        title = "Today",
                        onIntent = {},
                    )
                }
            }

            scrollToEnd()

            val row = unclippedBoundsOfTag(TestTags.taskItem(LAST_TASK_TITLE))
            val root = mainRootBounds()
            // Only the list's own 8 dp of padding is left below the last row:
            // a list must not keep blank space for a button that is not there.
            // The lower bound keeps the check from passing on a gap that is
            // not a gap at all.
            val gap = root.bottom - row.bottom
            assertTrue(
                gap >= 0.dp && gap <= 8.dp + 2.dp,
                "the list kept $gap below its last row with no button on screen",
            )
        }

    /**
     * Renders the agenda of [preset] with the shell's button over it, scrolls
     * to the end, and asserts the last row ends above the button.
     */
    private fun assertLastRowAboveFab(
        preset: AgendaDefinition,
        title: String,
    ) = runIsolatedComposeTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
        setContent {
            val footprint = remember { FabFootprint() }
            CompositionLocalProvider(LocalFabBottomSpace provides footprint.bottomSpace()) {
                Box(Modifier.fillMaxSize()) {
                    AgendaContent(
                        state = agendaState(preset),
                        title = title,
                        onIntent = {},
                    )
                    // The shell's button, in the shell's place: 16 dp above the
                    // bottom edge, at the end of the row.
                    Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                        FloatingActionButton(
                            onClick = {},
                            modifier =
                                Modifier
                                    .testTag(TestTags.TASKS_FAB)
                                    .onSizeChanged { footprint.onMeasured(it.height) },
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add task")
                        }
                    }
                }
            }
        }

        scrollToEnd()

        val row = unclippedBoundsOfTag(TestTags.taskItem(LAST_TASK_TITLE))
        val fab = unclippedBoundsOfTag(TestTags.TASKS_FAB)
        assertTrue(
            row.bottom <= fab.top,
            "the last row ends at ${row.bottom}, the button starts at ${fab.top}",
        )
    }

    /** Drags the list to its end: the last row only rests there once. */
    private fun DesktopComposeUiTest.scrollToEnd() {
        repeat(4) {
            onNode(hasScrollAction()).performTouchInput { swipeUp() }
        }
    }
}

/** A phone's width — the width the button and the last row collide at. */
private const val PHONE_WIDTH = 360

private const val PHONE_HEIGHT = 640

private const val LAST_TASK_TITLE = "Last task"

/**
 * The agenda of [preset] with its tasks in the last section, so the last row
 * of the list is a task row and not a section header.
 */
private fun agendaState(preset: AgendaDefinition): AgendaUiState.Loaded {
    val last = preset.sections.last()
    val sections =
        preset.sections.map { section ->
            RenderedSection(
                id = section.effectiveId,
                name = section.name,
                tasks = if (section == last) taskRows() else persistentListOf(),
            )
        }
    return AgendaUiState.Loaded(
        sections = sections.toImmutableList(),
        today = LocalDate(2026, 10, 14),
    )
}

/** More rows than fit on a phone, so the list has to scroll. */
private fun taskRows(): ImmutableList<AgendaRowItem> =
    (1..12)
        .map { index ->
            val title = if (index == 12) LAST_TASK_TITLE else "Task $index"
            AgendaRowItem(
                task =
                    Task(
                        id = TaskId("task-$index"),
                        title = title,
                        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                        userId = UserId.anonymous,
                    ),
            )
        }.toImmutableList()
