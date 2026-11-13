package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectDocument
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clearTag
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.countNodesWithTagInAnyRoot
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.scrollToTag
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import com.singularity.todo.test.helpers.typeIntoTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.koin.core.Koin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Desktop automation for scenario `TASK-PICKER-01` — see
 * `scripts/src/traceability/scenarios/tasks/picker/TASK-PICKER-01.yaml`.
 *
 * The `@DisplayName` above is the linkage: the id is the **first token** of the
 * string.
 *
 * ## What this drives
 *
 * The real task detail screen and the real picker, through the real repository:
 * the project row opens the sheet, the sheet hands a project id over on select,
 * and the id reaches the task's own write path. The picker is `commonMain`, so
 * this is the shared drawing on both platforms; the sheet opens its own window
 * on desktop, which the desktop tier can reach (`SheetReachabilityTest` pins
 * that convention).
 *
 * ## The edge tap
 *
 * The comparison in #2862 measured a real defect: a tap 7 px inside a child row
 * opened its parent, because the parent's 48 dp expander target reached 15 dp
 * into the next row. Android's menu no longer draws children, so that list
 * cannot repeat the defect — but the picker draws parent and child rows next to
 * each other, and it is where the claim still has to hold. The tap below lands
 * 2 px inside the Compose row's top edge: the row's own bounds, whatever is
 * painted there. If a parent ever reached into the child's row, the task would
 * end up in Android and the assertion would say so.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskProjectPickerScenarioTest {
    @Test
    @DisplayName("TASK-PICKER-01 a task's project is chosen from the hierarchy, and search keeps the path")
    fun a_tasks_project_is_chosen_from_the_hierarchy() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
            seedHierarchy(koin)
            val robot = tasks(koin)
            robot.given(due = today, title = "Buy milk")
            val taskId = requireNotNull(robot.lastTaskId)
            openTask("Buy milk")
            openProjectPicker()

            // ── the hierarchy, at depth ─────────────────────────────────────
            awaitTag(row("p-work"))
            awaitTag(row("p-android"))
            awaitTag(row("p-compose"))
            val workLeft = titleLeftInRow(row("p-work"), "Work")
            val androidLeft = titleLeftInRow(row("p-android"), "Android")
            val composeLeft = titleLeftInRow(row("p-compose"), "Compose")
            assertTrue(
                workLeft < androidLeft && androidLeft < composeLeft,
                "each level is indented past its parent: Work=$workLeft " +
                    "Android=$androidLeft Compose=$composeLeft",
            )

            // ── search keeps the ancestor, not the siblings ─────────────────
            typeIntoTag(TestTags.PROJECT_PICKER_SEARCH, "notes")
            awaitTag(row("p-dup-work"))
            awaitTag(row("p-dup-home"))
            // The same name under two parents is told apart by its path, drawn in
            // the matching row rather than anywhere else in the scene. Scoping the
            // lookup to the row is what stops the main window's text from passing it.
            assertPathInRow(row("p-dup-work"), "Work")
            assertPathInRow(row("p-dup-home"), "Home")
            assertEquals(0, countNodesWithTagInAnyRoot(row("p-work")), "a non-matching root is not a result")
            assertEquals(0, countNodesWithTagInAnyRoot(row("p-android")), "a non-matching child is not a result")
            assertEquals(
                0,
                countNodesWithTagInAnyRoot(row("p-compose")),
                "an unrelated sibling of a match is not a result",
            )

            // ── clearing restores the hierarchy ─────────────────────────────
            clearTag(TestTags.PROJECT_PICKER_SEARCH)
            awaitTag(row("p-work"))
            awaitTag(row("p-android"))
            awaitTag(row("p-compose"))

            // ── the edge of a child row belongs to the child ────────────────
            awaitTag(row("p-compose")).performTouchInput {
                // 2 px inside the row's top edge, in the row's own coordinates.
                click(Offset(width / 2f, 2f))
            }
            assertTaskProject(koin, taskId, COMPOSE)
            scrollToTag(TestTags.TASK_EDITOR_PROJECT_ROW)
            assertTextDisplayed("Compose")

            // ── the current choice is visible on the way back in ────────────
            openProjectPicker()
            awaitTag(row("p-compose")).assertIsSelected()
            awaitTag(TestTags.PROJECT_PICKER_NONE).assertIsNotSelected()
            awaitTag(TestTags.PROJECT_PICKER_INBOX).assertIsNotSelected()
            assertEquals(
                0,
                countNodesWithTagInAnyRoot(row("p-inbox")),
                "Inbox is a system destination, not a project row of its own",
            )

            // ── No project is a choice, not an absence ──────────────────────
            clickTag(TestTags.PROJECT_PICKER_NONE)
            assertTaskProject(koin, taskId, null)
            scrollToTag(TestTags.TASK_EDITOR_PROJECT_ROW)
            assertTextDisplayed("No project")
        }

    /** Opens the editor of a seeded task — that is where the project row lives. */
    private fun DesktopComposeUiTest.openTask(title: String) {
        tapTab("Today")
        awaitTag(TestTags.taskItem(title)).performClick()
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)
    }

    /** Opens the place where the task's project is chosen. */
    private fun DesktopComposeUiTest.openProjectPicker() {
        scrollToTag(TestTags.TASK_EDITOR_PROJECT_ROW)
        clickTag(TestTags.TASK_EDITOR_PROJECT_ROW)
        awaitTag(TestTags.PROJECT_PICKER_SEARCH)
    }

    /** Asserts a search result draws [path] as its ancestor context, inside its own row. */
    private fun DesktopComposeUiTest.assertPathInRow(
        rowTag: String,
        path: String,
    ) {
        onAllNodes(
            hasText(path) and hasAnyAncestor(hasTestTag(rowTag)),
            useUnmergedTree = true,
        ).onFirst().assertExists()
    }

    /** The left edge of a row's title, which is what the indent moves. */
    private fun DesktopComposeUiTest.titleLeftInRow(
        rowTag: String,
        title: String,
    ): Dp =
        onAllNodes(
            hasText(title) and hasAnyAncestor(hasTestTag(rowTag)),
            useUnmergedTree = true,
        ).onFirst().getUnclippedBoundsInRoot().left

    /**
     * Asserts the project the task is stored with.
     *
     * Waits on the repository's own emission rather than on the row: the row
     * says what the editor was told, and the write is what the user keeps.
     */
    private suspend fun assertTaskProject(
        koin: Koin,
        taskId: TaskId,
        expected: ProjectId?,
    ) = realTime {
        val task =
            withTimeout(TIMEOUT_MS) {
                koin
                    .get<TaskRepository>()
                    .observeAll()
                    .first { tasks -> tasks.any { it.id == taskId && it.projectId == expected } }
                    .first { it.id == taskId }
            }
        assertEquals(expected, task.projectId, "the task is stored with the chosen project")
    }

    /**
     * Runs a repository wait on real time.
     *
     * The harness runs the test body under a virtual-time scheduler, so a
     * `withTimeout` inside it burns its budget without letting the write's
     * dispatcher run. The write itself is real.
     */
    private suspend fun <T> realTime(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { block() }

    /** The three-level chain and the duplicate names the search has to tell apart. */
    private suspend fun seedHierarchy(koin: Koin) {
        seedProject(koin, id = "p-work", name = "Work")
        seedProject(koin, id = "p-android", name = "Android", parent = "p-work")
        seedProject(koin, id = "p-compose", name = "Compose", parent = "p-android")
        seedProject(koin, id = "p-home", name = "Home")
        seedProject(koin, id = "p-dup-work", name = "Notes", parent = "p-work")
        seedProject(koin, id = "p-dup-home", name = "Notes", parent = "p-home")
        seedProject(koin, id = "p-inbox", name = "Inbox", isDefault = true)
    }

    private suspend fun seedProject(
        koin: Koin,
        id: String,
        name: String,
        parent: String? = null,
        isDefault: Boolean = false,
    ) {
        val epoch = Instant.fromEpochMilliseconds(0)
        koin.get<ProjectsRepository>().upsert(
            ProjectDocument(
                Project(
                    id = ProjectId.fromString(id),
                    name = name,
                    color = 0xFF2196F3.toInt(),
                    createdAt = epoch,
                    updatedAt = epoch,
                    parentId = parent?.let(ProjectId::fromString),
                    isDefault = isDefault,
                    userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value,
                ),
                emptySet(),
            ),
        )
    }

    private fun row(projectId: String) = TestTags.projectPickerRow(projectId)

    private companion object {
        val CLOCK: FakeClock = FakeClock(Instant.parse("2026-09-16T10:00:00Z"))
        val today: LocalDate = LocalDate(2026, 9, 16)

        val COMPOSE = ProjectId.fromString("p-compose")

        /** How long a write through the repository may take to become visible. */
        const val TIMEOUT_MS = 10_000L
    }
}
