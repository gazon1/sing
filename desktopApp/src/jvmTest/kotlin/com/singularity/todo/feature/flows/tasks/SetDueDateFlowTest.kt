package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.seedTask
import org.junit.Test

/**
 * Desktop mirror of `Maestro/flows/tasks/02-set-due-date.yaml`, scoped to the
 * part that this MR unblocked.
 *
 * The tag applied here is `TASK_EDITOR_DUE_ROW`. The Android flow goes further —
 * it opens the Material3 date picker and picks a day — and that half is
 * deliberately not mirrored: the picker's day cells expose no testTag (Material3
 * owns them) and no `content-desc` selector exists for Maestro or Compose UI
 * Test, which is why the Android flow resorts to a regex over the year. That
 * workaround is not worth copying into a second platform; the picker is
 * Material3's, and its behaviour is already pinned there.
 *
 * What this flow does assert is the part that was previously unaddressable at
 * all: that the due-date row carries a stable id, and that the placeholder
 * label is reachable before a date is set.
 */
@OptIn(ExperimentalTestApi::class)
class SetDueDateFlowTest {

    /**
     * Covers the dated case only.
     *
     * The undated case — placeholder label "Добавить дату" on an undated task —
     * is not covered here because it is blocked on the NoDate question, not on
     * the tag: an undated task does not reach the agenda at all, so the editor
     * never opens. See `deferred-backlog.md#nodate-steps-2-4`. Write this case
     * when that lands, not before; seeding an undated task and asserting on it
     * would fail for a reason unrelated to the row.
     */
    @Test
    fun a_dated_task_shows_its_date_instead_of_the_placeholder() = runDesktopAppTest { koin ->
        val today = todayInSystemZone()
        koin.seedTask(id = "due-today", title = "Buy milk", dueDate = today)

        awaitTag(TestTags.taskItem("Buy milk")).performClick()
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

        onNodeWithTag(TestTags.TASK_EDITOR_DUE_ROW).assertIsDisplayed()
        // The row renders the ISO date, not the placeholder.
        onNodeWithText(today.toString()).assertIsDisplayed()
        onNodeWithText("Добавить дату").assertDoesNotExist()
    }
}
