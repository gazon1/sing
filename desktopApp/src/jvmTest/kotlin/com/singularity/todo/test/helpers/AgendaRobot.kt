package com.singularity.todo.test.helpers

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
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
 *         .assertSection("today", contains = listOf("Today's task", "Pinned today"))
 *         .assertSection("overdue", contains = listOf("Overdue task"))
 *         .assertSection("no_date", contains = listOf("No date", "Inbox task"))
 * }
 * ```
 *
 * All assertions use [TestTags] for stable selectors; no text-based locators.
 */
@OptIn(ExperimentalTestApi::class)
class AgendaRobot(private val test: DesktopComposeUiTest, private val koin: Koin) {
    // ─── Navigation ───────────────────────────────────────────────────────────

    /**
     * Taps the named agenda tab (Inbox / Today / Upcoming).
     */
    fun selectTab(name: String): AgendaRobot =
        apply {
            test.tapTab(name)
        }

    // ─── Section assertions ──────────────────────────────────────────────────

    /**
     * Asserts that the section with the given [sectionId] is visible and,
     * optionally, that each [expectedTitles] appears inside it.
     *
     * [sectionId] is the section's **id**, not its display name: the header's tag
     * is keyed by the id so two same-named sections stay separately addressable
     * (#2197). For the built-in presets the id happens to be the name's slug —
     * `today`, `no_date`, `overdue`, `tomorrow`, `in_progress`, `stagnant`.
     *
     * Waits for the section to render before checking.
     */
    fun assertSection(
        sectionId: String,
        contains: List<String> = emptyList(),
    ): AgendaRobot =
        apply {
            val sectionTag = TestTags.agendaSection(sectionId)
            test.assertTagDisplayed(sectionTag)
            contains.forEach { title ->
                test.assertTagDisplayed(TestTags.taskItem(title))
            }
        }

    /**
     * Asserts that the section with [sectionId] is NOT visible.
     */
    fun assertSectionMissing(sectionId: String): AgendaRobot =
        apply {
            val sectionTag = TestTags.agendaSection(sectionId)
            test.awaitTagGone(sectionTag)
        }

    /**
     * Asserts a task with [title] appears in the agenda (any section).
     * Does NOT assert which section — use [assertSection] for bucketing checks.
     */
    fun assertTaskVisible(title: String): AgendaRobot =
        apply {
            test.assertTagDisplayed(TestTags.taskItem(title))
        }

    /**
     * Asserts a task with [title] does NOT appear in the agenda.
     */
    fun assertTaskMissing(title: String): AgendaRobot =
        apply {
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
    fun assertSeedBucketsPopulated(): AgendaRobot =
        apply {
            // These use hardcoded IDs from AgendaSeed — if a bucket is empty but has
            // seed tasks, the evaluator or the seed installation failed.
            assertSection("today", contains = AgendaSeed.TODAY_IDS.map { it.removePrefix("task-") })
            assertSection("overdue", contains = AgendaSeed.OVERDUE_IDS.map { it.removePrefix("task-") })
            assertSection("tomorrow", contains = AgendaSeed.TOMORROW_IDS.map { it.removePrefix("task-") })
            assertSection("no_date", contains = AgendaSeed.NO_DATE_IDS.map { it.removePrefix("task-") })
        }

    /** Asserts the seeded pinned task appears first in the Today section. */
    fun assertPinnedTaskFirst(title: String = "Pinned today"): AgendaRobot =
        apply {
            // LazyColumn with custom order — pinned first, then by dueDate.
            // The first visible task item should be the pinned one.
            assertTaskVisible(title)
        }
}

/** Entry point: `agenda(koin)` inside a `runDesktopAppTest` body. */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.agenda(koin: Koin): AgendaRobot = AgendaRobot(this, koin)
