@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.tasks.presentation.components.list

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import com.singularity.todo.test.helpers.assertContentDescriptionNotExists
import com.singularity.todo.test.helpers.assertNodeDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.assertTextNotExists
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * The mark a list row carries: that the task repeats, and which project it is.
 *
 * The row is the surface the user reads fastest, and the two marks are the whole
 * point of the feature's cheap half — "I cannot tell what this row is". These are
 * component tests: they drive the row itself, with no Koin and no navigation, so a
 * failure localises to the mark rather than to the shell around it.
 */
@Tag("fast")
class TaskMetaRowBadgeTest {

    /** The repeat badge's accessible name — what a screen reader reads for the mark. */
    private val repeat = hasContentDescription("Повторяющаяся задача")

    // ─── The repeat badge ─────────────────────────────────────────────────────

    @Test
    fun `a repeating task's meta row carries the repeat badge`() = runIsolatedComposeTest {
        setContent {
            TaskMetaRow(
                dueLabel = null,
                project = null,
                isRecurring = true,
                isOverdue = false,
            )
        }

        assertNodeDisplayed(repeat, "the repeat badge")
    }

    @Test
    fun `a one-off task's meta row carries no repeat badge`() = runIsolatedComposeTest {
        setContent {
            TaskMetaRow(
                dueLabel = "Пн, 15 июн 2026",
                project = null,
                isRecurring = false,
                isOverdue = false,
            )
        }

        assertContentDescriptionNotExists("Повторяющаяся задача")
    }

    @Test
    fun `the repeat badge shows on a row with nothing else to say`() = runIsolatedComposeTest {
        // No date, no project, not blocked, not in a series. The badge is the only
        // reason the meta row exists at all — without opening the gate for it, this
        // row would draw nothing.
        setContent {
            TaskMetaRow(
                dueLabel = null,
                project = null,
                isRecurring = true,
                isOverdue = false,
                isBlocked = false,
                seriesDates = null,
            )
        }

        assertNodeDisplayed(repeat, "the repeat badge on an otherwise empty row")
    }

    // ─── The project badge ────────────────────────────────────────────────────

    @Test
    fun `a project row names the project`() = runIsolatedComposeTest {
        setContent {
            TaskMetaRow(
                dueLabel = "Пн, 15 июн 2026",
                project = "Семья",
                isRecurring = false,
                isOverdue = false,
                projectColor = 0xFF6A4C93.toInt(),
            )
        }

        assertTextDisplayed("Семья")
    }

    @Test
    fun `a row with no project prints no placeholder`() = runIsolatedComposeTest {
        // The row used to print the literal "Без проекта" next to the date of every
        // agenda row. The placeholder leaves with the badge.
        setContent {
            TaskMetaRow(
                dueLabel = "Пн, 15 июн 2026",
                project = null,
                isRecurring = false,
                isOverdue = false,
            )
        }

        assertTextNotExists("Без проекта")
        assertTextDisplayed("Пн, 15 июн 2026")
    }

    @Test
    fun `a repeating project row carries both marks`() = runIsolatedComposeTest {
        setContent {
            TaskMetaRow(
                dueLabel = "Пн, 15 июн 2026",
                project = "Семья",
                isRecurring = true,
                isOverdue = false,
                projectColor = 0xFF6A4C93.toInt(),
            )
        }

        assertNodeDisplayed(repeat, "the repeat badge")
        assertNodeDisplayed(hasText("Семья"), "the project badge")
        assertTextDisplayed("Пн, 15 июн 2026")
    }

    @Test
    fun `the project name truncates rather than pushing the date out`() = runIsolatedComposeTest {
        // A long project name must not push the date out of the row: the badge is
        // given the remaining width and ellipsises. Both are on screen.
        setContent {
            TaskMetaRow(
                dueLabel = "Пн, 15 июн 2026",
                project = "Очень длинное название проекта которое точно не влезет в строку",
                isRecurring = false,
                isOverdue = false,
                projectColor = 0xFF6A4C93.toInt(),
            )
        }

        assertTextDisplayed("Пн, 15 июн 2026")
        assertNodeDisplayed(
            hasText("Очень длинное название", substring = true),
            "the project name is shown, truncated",
        )
    }
}
