@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.tasks.presentation.components.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.settings.ThemeMode
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.TouchTarget
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskMarking
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing
import com.singularity.todo.test.helpers.assertNodeDisplayed
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.touchBoundsOfTagInUnmergedTree
import com.singularity.todo.test.helpers.unclippedBoundsOfTagInUnmergedTree
import kotlinx.collections.immutable.persistentSetOf
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The row, measured: how tall it is, how a title wraps, and what the metadata
 * row gives up when the width runs out.
 *
 * These are the numbers the 2026-11-09 comparison complained about — a one-line
 * title that spent 83 dp on screen, a due date pushed out of the row by the
 * marks beside it — so they are asserted as measurements rather than read off
 * the source. A test that re-derives the expected value from the tokens it
 * measures would only prove that the arithmetic still balances; the bounds come
 * from the laid-out tree, so a row that grew for any other reason fails here.
 *
 * The width is fixed in dp (a 360 dp phone) rather than in pixels, so the same
 * numbers hold whatever density the test window runs at.
 *
 * **The unmerged tree.** The row is clickable, and a clickable swallows its
 * descendants' semantics: in the merged tree the row's own node carries the
 * title's text and the whole row's bounds, so the title's *own* box — the thing
 * this file measures — is only visible in the unmerged tree.
 */
@Tag("slow")
@Tag("slow:compose")
class TaskRowDensityTest {
    // ─── Height ───────────────────────────────────────────────────────────────

    @Test
    fun `a one-line row is 72 dp tall`() =
        runIsolatedComposeTest {
            setContent {
                Themed {
                    PhoneRow(task = task(title = ONE_LINE))
                }
            }

            val row = unclippedBoundsOfTagInUnmergedTree(TestTags.taskItem(ONE_LINE))
            assertDp(MAX_ONE_LINE_ROW, row.heightDp(), "a one-line row's height")
            assertTrue(
                row.heightDp() <= MAX_ONE_LINE_ROW,
                "a one-line row measured ${row.heightDp()}, the bound is $MAX_ONE_LINE_ROW",
            )
        }

    @Test
    fun `a one-line row still gives the checkbox its 48 dp target`() =
        runIsolatedComposeTest {
            setContent {
                Themed {
                    PhoneRow(task = task(title = ONE_LINE))
                }
            }

            // The touch bounds, not the bounds: the checkbox *draws* a 22 dp
            // square, but the pointer area around it is expanded to the
            // platform's minimum touch target. The row's height is that target
            // plus the padding above and below — 48 + 12 + 12 = 72 — so the
            // target is what makes the row as tall as it is.
            val checkbox = touchBoundsOfTagInUnmergedTree(TestTags.taskCheckbox(ONE_LINE))
            assertDp(TouchTarget.MinSize, checkbox.widthDp(), "the checkbox's touch target width")
            assertDp(TouchTarget.MinSize, checkbox.heightDp(), "the checkbox's touch target height")
        }

    // ─── Titles ───────────────────────────────────────────────────────────────

    @Test
    fun `a two-line title is fully displayed without making the row taller`() =
        runIsolatedComposeTest {
            setContent {
                Themed {
                    Column {
                        ReferenceTitle(TWO_LINE)
                        PhoneRow(task = task(title = TWO_LINE))
                    }
                }
            }

            val titles = onAllNodesWithText(TWO_LINE, useUnmergedTree = true)
            val reference = titles[0].getUnclippedBoundsInRoot()
            val rendered = titles[1].getUnclippedBoundsInRoot()

            assertDp(LINE_HEIGHT * 2, reference.heightDp(), "the reference title's height")
            assertDp(reference.heightDp(), rendered.heightDp(), "the row's title height — the title is cut")
            // Two lines still fit inside the 48 dp the checkbox's touch target
            // takes, so the row does not grow yet — it is the same 72 dp.
            assertDp(
                MAX_ONE_LINE_ROW,
                unclippedBoundsOfTagInUnmergedTree(TestTags.taskItem(TWO_LINE)).heightDp(),
                "a two-line row's height",
            )
        }

    @Test
    fun `a three-line title is fully displayed and the row grows with it`() =
        runIsolatedComposeTest {
            setContent {
                Themed {
                    Column {
                        ReferenceTitle(THREE_LINE)
                        PhoneRow(task = task(title = THREE_LINE))
                    }
                }
            }

            val titles = onAllNodesWithText(THREE_LINE, useUnmergedTree = true)
            val reference = titles[0].getUnclippedBoundsInRoot()
            val rendered = titles[1].getUnclippedBoundsInRoot()

            assertDp(LINE_HEIGHT * 3, reference.heightDp(), "the reference title's height")
            assertDp(reference.heightDp(), rendered.heightDp(), "the row's title height — the title is cut")
            // The title is now taller than the checkbox's 48 dp target, so the
            // row follows the title: 66 dp of title plus 12 dp above and below.
            assertDp(
                LINE_HEIGHT * 3 + TaskListSpacing.Md * 2,
                unclippedBoundsOfTagInUnmergedTree(TestTags.taskItem(THREE_LINE)).heightDp(),
                "a three-line row's height",
            )
        }

    @Test
    fun `the checkbox stays on the first line of a three-line title`() =
        runIsolatedComposeTest {
            setContent {
                Themed {
                    PhoneRow(task = task(title = THREE_LINE))
                }
            }

            // The touch target, not the drawn 22 dp square: the square is
            // centred inside the 48 dp target, so its top sits 13 dp lower than
            // the row's top edge — but the *target* starts at the title's line,
            // which is what "the checkbox stays on the first line" means.
            val checkbox = touchBoundsOfTagInUnmergedTree(TestTags.taskCheckbox(THREE_LINE))
            val title = onAllNodesWithText(THREE_LINE, useUnmergedTree = true)[0].getUnclippedBoundsInRoot()

            assertDp(
                checkbox.top,
                title.top,
                "the checkbox's touch target top against the title's top",
            )
        }

    // ─── Metadata ─────────────────────────────────────────────────────────────

    @Test
    fun `the due date keeps its width and the project is the one that gives way`() =
        runIsolatedComposeTest {
            setContent {
                Themed {
                    Column {
                        // The same meta row twice: once in a row that is too narrow,
                        // once in one that is not. The date must measure the same in
                        // both; the project must not.
                        Box(Modifier.width(TITLE_COLUMN)) { MetaRow() }
                        Box(Modifier.width(PHONE_WIDTH * 2)) { MetaRow() }
                    }
                }
            }

            val dates = onAllNodesWithText(DUE, useUnmergedTree = true)
            val projects = onAllNodesWithText(PROJECT, useUnmergedTree = true)

            assertDp(
                dates[1].getUnclippedBoundsInRoot().widthDp(),
                dates[0].getUnclippedBoundsInRoot().widthDp(),
                "the due date's width in a narrow row",
            )
            assertTrue(
                projects[0].getUnclippedBoundsInRoot().widthDp() <
                    projects[1].getUnclippedBoundsInRoot().widthDp(),
                "the project name was not the field that gave way its width",
            )
        }

    @Test
    fun `the due date is on screen next to a long project and the repeat and series marks`() =
        runIsolatedComposeTest {
            setContent {
                Themed {
                    Box(Modifier.width(TITLE_COLUMN)) { MetaRow() }
                }
            }

            assertNodeDisplayed(hasText(DUE), "the due date")
            assertNodeDisplayed(hasText(PROJECT), "the project name")
            assertNodeDisplayed(hasContentDescription(RECURRING_MARK), "the repeat mark")
            assertNodeDisplayed(hasContentDescription(SERIES_MARK), "the series mark")
        }

    @Test
    fun `the due date comes before the project`() =
        runIsolatedComposeTest {
            setContent {
                Themed {
                    Box(Modifier.width(TITLE_COLUMN)) { MetaRow() }
                }
            }

            val date = onAllNodesWithText(DUE, useUnmergedTree = true)[0].getUnclippedBoundsInRoot()
            val project = onAllNodesWithText(PROJECT, useUnmergedTree = true)[0].getUnclippedBoundsInRoot()

            assertTrue(
                date.right <= project.left,
                "the date ends at ${date.right}, the project starts at ${project.left}",
            )
        }

    // ─── Priority ─────────────────────────────────────────────────────────────

    @Test
    fun `the agenda row shows the priority once, as a marker`() =
        runIsolatedComposeTest {
            setContent {
                Themed {
                    PhoneRow(task = task(title = PRIORITY_TITLE, priority = TaskPriority.Medium))
                }
            }

            val markers = priorityMarkers()

            assertEquals(1, markers.size, "the row must carry exactly one priority signal")
            assertNull(
                markers.first().config.getOrNull(SemanticsActions.OnClick),
                "the priority marker must not be a click target of its own — " +
                    "priority is edited in the task editor",
            )
            assertTrue(
                onAllNodesWithText("Medium", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
                "the row must not repeat the priority as text",
            )
        }

    @Test
    fun `the agenda row shows the priority once in the dark theme too`() =
        runIsolatedComposeTest {
            setContent {
                Themed(dark = true) {
                    PhoneRow(task = task(title = PRIORITY_TITLE, priority = TaskPriority.Medium))
                }
            }

            assertEquals(
                1,
                priorityMarkers().size,
                "the row must carry exactly one priority signal",
            )
        }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** The priority markers in the tree — one per priority signal the row draws. */
    private suspend fun androidx.compose.ui.test.DesktopComposeUiTest.priorityMarkers() =
        onAllNodes(hasContentDescription(MEDIUM_PRIORITY), useUnmergedTree = true)
            .fetchSemanticsNodes()

    /** A row as it is drawn on a phone: the width the audited device had. */
    @Composable
    private fun PhoneRow(task: TaskUi) {
        Box(Modifier.width(PHONE_WIDTH)) {
            TaskRowFlat(task = task, onToggleCompleted = {}, onClick = {})
        }
    }

    /**
     * The same title with no line limit, at the width the row gives it.
     *
     * The row's title is measured against this one: equal heights mean the row
     * showed the whole title, a shorter one means the row cut it.
     */
    @Composable
    private fun ReferenceTitle(text: String) {
        Text(
            text = text,
            fontSize = 16.sp,
            lineHeight = LINE_HEIGHT.value.sp,
            maxLines = Int.MAX_VALUE,
            modifier = Modifier.width(TITLE_COLUMN),
        )
    }

    /** A meta row with everything on it: a date, a repeat, a series, a project. */
    @Composable
    private fun MetaRow() {
        TaskMetaRow(
            dueLabel = DUE,
            project = PROJECT,
            markings = persistentSetOf(TaskMarking.Recurring),
            seriesDates = 5,
            projectColor = 0xFF6A4C93.toInt(),
        )
    }

    @Composable
    private fun Themed(
        dark: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        SingularityTheme(themeMode = if (dark) ThemeMode.Dark else ThemeMode.Light) {
            content()
        }
    }

    private fun task(
        title: String,
        priority: TaskPriority = TaskPriority.None,
    ) = TaskUi(
        id = TaskId.fromString("1"),
        title = title,
        project = null,
        dueLabel = null,
        priority = priority,
    )

    /**
     * Bounds travel through pixels before they come back as dp, so an exact
     * float comparison would be an assertion about the test window's density.
     */
    private fun assertDp(
        expected: Dp,
        actual: Dp,
        what: String,
    ) {
        assertEquals(expected.value, actual.value, 0.5f, what)
    }

    private companion object {
        /** The width of the audited device. */
        val PHONE_WIDTH = 360.dp

        /**
         * The width the row's text column gets: the row's own padding, the
         * checkbox's touch target and the gap after it.
         */
        val TITLE_COLUMN =
            PHONE_WIDTH - TaskListSpacing.Lg * 2 - TouchTarget.MinSize -
                (TaskListSpacing.Md + TaskListSpacing.Xs)

        /** A one-line row: the 48 dp target plus 12 dp above and below. */
        val MAX_ONE_LINE_ROW = 72.dp

        val LINE_HEIGHT = 22.dp

        const val ONE_LINE = "Buy milk"
        const val TWO_LINE = "Buy milk and bread and eggs for the weekend"
        const val THREE_LINE =
            "Buy milk and bread and eggs for the weekend and call the dentist about the check up"
        const val PRIORITY_TITLE = "Call the dentist"
        const val DUE = "Пн, 15 июн 2026"
        const val PROJECT = "Очень длинное название проекта которое точно не влезет в строку"
        const val MEDIUM_PRIORITY = "Medium priority"
        const val RECURRING_MARK = "Recurring task"
        const val SERIES_MARK = "series · 5 dates"
    }
}

/**
 * The rect's width in dp.
 *
 * `DpRect.width` is an extension property, and importing it next to
 * `Modifier.width` — which this file also uses — is a name clash the compiler
 * resolves by receiver anyway. Spelling the arithmetic out keeps the file
 * readable and the imports honest.
 */
private fun DpRect.widthDp(): Dp = right - left

/** The rect's height in dp. The counterpart of [widthDp]. */
private fun DpRect.heightDp(): Dp = bottom - top
