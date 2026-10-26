package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tags.components.TagsPickerTestTags
import com.singularity.todo.feature.tags.domain.TagDomain
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertButtonNotEnabled
import com.singularity.todo.test.helpers.awaitAnyDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagTextEquals
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.clickTagFirst
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.scrollToTag
import com.singularity.todo.test.helpers.tasks
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
 * Desktop automation for scenario `TASK-TAG-01` — see
 * `scripts/src/traceability/scenarios/tasks/tag/TASK-TAG-01.yaml`.
 *
 * The `@DisplayName` above is the linkage: the id is the **first token** of the
 * string, and that strictness is the contract — the traceability scanner
 * rejects an id anywhere else rather than guessing.
 *
 * ## What this drives
 *
 * The real picker, through the real repository and the real DI graph: the tag
 * is created where the tags of a task are chosen, and it is applied to the task
 * by the path that already existed — the sheet hands its selection over on
 * "Done", and that selection is what the task's tags become. No new intent, no
 * new ViewModel, no second write path.
 *
 * ## Why the a11y pass is off
 *
 * The create dialog is the one the tags screen already uses, and its colour
 * swatches are clickable boxes that announce nothing. That is a property of the
 * dialog, not of this flow, and switching the pass on here would report the
 * dialog's backlog against a scenario about creating a tag.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskTagScenarioTest {

    @Test
    @DisplayName("TASK-TAG-01 a new tag is created where the task's tags are chosen")
    fun a_new_tag_is_created_where_the_tags_are_chosen() =
        runDesktopAppTest(clock = CLOCK) { koin ->
            tasks(koin).given(due = today, title = "Buy milk")
            openEditor("Buy milk")

            // ── the tag that does not exist yet ─────────────────────────────
            openTagPicker()
            createTagInPicker("invoice")

            // It is in the list to choose from without the sheet being reopened.
            awaitAnyDisplayed(TagsPickerTestTags.row("invoice"))

            clickTagFirst(TagsPickerTestTags.DONE)
            assertTaskCarriesTag(koin, taskTitle = "Buy milk", tagName = "invoice")

            // ── the same name again is refused ──────────────────────────────
            openTagPicker()
            createTagInPicker("Invoice")
            awaitTagTextEquals(
                TagsPickerTestTags.CREATE_MESSAGE,
                "Tag \"invoice\" already exists — the existing tag was selected.",
            )
            assertEquals(
                1,
                tagCount(koin),
                "a second tag with the same name must not be created",
            )
            clickTagFirst(TagsPickerTestTags.CANCEL)

            // ── a blank name is refused, and the reason is stated ───────────
            openTagPicker()
            clickTagFirst(TagsPickerTestTags.CREATE)
            assertButtonNotEnabled("Create")
            awaitTagTextEquals(TagsPickerTestTags.CREATE_HINT, TagDomain.BLANK_NAME_MESSAGE)
            assertEquals(
                1,
                tagCount(koin),
                "a refused name must not create a tag",
            )
        }

    /** Opens the editor of a seeded task — that is where the attribute rows live. */
    private fun DesktopComposeUiTest.openEditor(title: String) {
        awaitTag(TestTags.taskItem(title)).performClick()
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)
    }

    /**
     * Opens the place where the tags of the task are chosen.
     *
     * The row sits in the editor's scrolling attribute list, so it is brought
     * into view before it is pressed.
     */
    private fun DesktopComposeUiTest.openTagPicker() {
        scrollToTag(TestTags.TASK_EDITOR_TAGS_ROW)
        clickTag(TestTags.TASK_EDITOR_TAGS_ROW)
    }

    /** Creates a tag of that name from inside the picker. */
    private fun DesktopComposeUiTest.createTagInPicker(name: String) {
        clickTagFirst(TagsPickerTestTags.CREATE)
        typeTagName(name)
        clickText("Create")
    }

    /**
     * Types into the name field of the create dialog.
     *
     * The dialog opens its own window, so its field is the last editable node
     * in the scene: the app's own fields live in the main window, and the
     * dialog's window is created after it.
     */
    private fun DesktopComposeUiTest.typeTagName(value: String) {
        onAllNodes(hasSetTextAction()).onLast().performTextInput(value)
    }

    /** How many tags the user has. */
    private suspend fun tagCount(koin: Koin): Int = realTime {
        withTimeout(TIMEOUT_MS) { koin.get<TagsRepository>().observeAll().first().size }
    }

    /**
     * Asserts that the task carries the tag of that name.
     *
     * The wait is on the condition rather than on a read: the tag is applied
     * through the task's own write path, so the row shows the tag a moment
     * after the sheet hands the selection over.
     */
    private suspend fun assertTaskCarriesTag(koin: Koin, taskTitle: String, tagName: String) = realTime {
        val tagId = withTimeout(TIMEOUT_MS) {
            koin.get<TagsRepository>().observeAll()
                .first { tags -> tags.any { it.name == tagName } }
                .first { it.name == tagName }
                .id
        }
        val task = withTimeout(TIMEOUT_MS) {
            koin.get<TaskRepository>().observeAll()
                .first { tasks -> tasks.any { it.title == taskTitle && it.tags.contains(tagId) } }
                .first { it.title == taskTitle }
        }
        assertTrue(
            task.tags.contains(tagId),
            "the tag created in the picker must be applied to the task",
        )
    }

    /**
     * Runs a repository wait on real time.
     *
     * The harness executes the test body under a virtual-time scheduler (a
     * `withTimeout` inside it burns its budget without letting the write's
     * dispatcher run), so a wait for a *subsequent* emission has to leave the
     * virtual clock. The write itself is real: it goes through the repository
     * and the database.
     */
    private suspend fun <T> realTime(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { block() }

    private companion object {
        /** Mid-month, so no assertion in this file straddles a boundary. */
        val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK: FakeClock = FakeClock(FIXED_NOW)
        val today: LocalDate = LocalDate(2026, 9, 16)

        /** How long a write through the repository may take to become visible. */
        const val TIMEOUT_MS = 10_000L
    }
}
