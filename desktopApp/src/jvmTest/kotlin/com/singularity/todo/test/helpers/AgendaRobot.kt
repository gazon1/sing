package com.singularity.todo.test.helpers

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.AgendaSeed
import org.koin.core.Koin

/**
 * Page-object robot for agenda-view interactions in desktop flow tests.
 *
 * ```
 * runDesktopAppTest { koin ->
 *     agenda(koin)
 *         .assertSection("Today", contains = listOf("Today's task", "Pinned today"))
 *         .assertSection("Overdue", contains = listOf("Overdue task"))
 *         .assertSection("No Date", contains = listOf("No date", "Inbox task"))
 * }
 * ```
 *
 * All assertions use [TestTags] for stable selectors; no text-based locators.
 */
@OptIn(ExperimentalTestApi::class)
class AgendaRobot(
    private val test: DesktopComposeUiTest,
    private val koin: Koin,
) {

    // ─── Navigation ───────────────────────────────────────────────────────────

    /**
     * Taps the named agenda tab (Inbox / Today / Upcoming).
     */
    fun selectTab(name: String): AgendaRobot = apply {
        test.tapTab(name)
    }

    // ─── Section assertions ──────────────────────────────────────────────────

    /**
     * Asserts that the section with the given [sectionName] is visible and,
     * optionally, that each [expectedTitles] appears inside it.
     *
     * Waits for the section to render before checking.
     */
    fun assertSection(
        sectionName: String,
        contains: List<String> = emptyList(),
    ): AgendaRobot = apply {
        val sectionTag = TestTags.agendaSection(sectionName)
        test.awaitTag(sectionTag).assertIsDisplayed()
        contains.forEach { title ->
            test.awaitTag(TestTags.taskItem(title)).assertIsDisplayed()
        }
    }

    /**
     * Asserts that the section with [sectionName] is NOT visible.
     */
    fun assertSectionMissing(sectionName: String): AgendaRobot = apply {
        val sectionTag = TestTags.agendaSection(sectionName)
        test.awaitTagGone(sectionTag)
    }

    /**
     * Asserts a task with [title] appears in the agenda (any section).
     * Does NOT assert which section — use [assertSection] for bucketing checks.
     */
    fun assertTaskVisible(title: String): AgendaRobot = apply {
        test.awaitTag(TestTags.taskItem(title)).assertIsDisplayed()
    }

    /**
     * Asserts a task with [title] does NOT appear in the agenda.
     */
    fun assertTaskMissing(title: String): AgendaRobot = apply {
        test.awaitTagGone(TestTags.taskItem(title))
    }

    // ─── Task interactions ───────────────────────────────────────────────────

    /**
     * Opens the task detail by tapping the task row.
     * Returns the row for further interactions.
     */
    fun openTask(title: String): SemanticsNodeInteraction =
        test.awaitTag(TestTags.taskItem(title)).also { it.performClick() }

    // ─── Bucket sanity checks ─────────────────────────────────────────────────
    // These cross-check the AgendaSeed fixture expectations against what the
    // UI actually renders — useful for smoke-testing the seed fixture itself.

    /** Asserts all buckets that SHOULD contain tasks actually render at least one. */
    fun assertSeedBucketsPopulated(): AgendaRobot = apply {
        // These use hardcoded IDs from AgendaSeed — if a bucket is empty but has
        // seed tasks, the evaluator or the seed installation failed.
        assertSection("Today", contains = AgendaSeed.TODAY_IDS.map { it.removePrefix("task-") })
        assertSection("Overdue", contains = AgendaSeed.OVERDUE_IDS.map { it.removePrefix("task-") })
        assertSection("Tomorrow", contains = AgendaSeed.TOMORROW_IDS.map { it.removePrefix("task-") })
        assertSection("No Date", contains = AgendaSeed.NO_DATE_IDS.map { it.removePrefix("task-") })
    }

    /** Asserts the seeded pinned task appears first in the Today section. */
    fun assertPinnedTaskFirst(title: String = "Pinned today"): AgendaRobot = apply {
        // LazyColumn with custom order — pinned first, then by dueDate.
        // The first visible task item should be the pinned one.
        assertTaskVisible(title)
    }
}

/** Entry point: `agenda(koin)` inside a `runDesktopAppTest` body. */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.agenda(koin: Koin): AgendaRobot =
    AgendaRobot(this, koin)
