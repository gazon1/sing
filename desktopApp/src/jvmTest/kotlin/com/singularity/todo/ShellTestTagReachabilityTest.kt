package com.singularity.todo

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.assertContentDescriptionDisplayed
import com.singularity.todo.test.helpers.assertCurrentTab
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tagInventory
import com.singularity.todo.test.helpers.tasks
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The shell renders, and the test tags of the shared UI it hosts reach the
 * semantics tree — on the JVM, in seconds.
 *
 * ## Why this test exists
 *
 * The device tier (`AppStartupTest.the_shell_is_addressable_by_test_tag`) asks
 * whether the shell's tags reach the **accessibility** tree, by looking for a node
 * whose `resource-id` is `nav_tab_today`. That question is worth ten minutes of
 * emulator, and it has a portable half that does not need any of them: *does the
 * Compose test tag reach the semantics tree at all?* This test asks the portable
 * half, on the tier where the shared UI actually runs.
 *
 * ## What "the shell's tags" means on this tier — read this before trusting the test
 *
 * The desktop shell's own chrome carries **no** test tags: the drawer entries and
 * the FAB are addressed by content description (see `DesktopShell`). The tags that
 * live in this tier are the ones the *shared* UI applies — the agenda top bar's
 * actions and the task row — and those are the ones this test asserts.
 *
 * The Android shell's tags (`nav_tab_*`, `nav_menu_button`, `tasks_fab`) are applied
 * in `shared/src/androidMain`, which this tier cannot render, and Android's
 * `testTagsAsResourceId` — the thing that turns a tag into a UIAutomator
 * `resource-id` — is a no-op on desktop (`mapTestTagsAsResourceIds()` returns the
 * node unchanged). So the device tier's third question stays where it is; this
 * test covers the half that can be covered without a device.
 *
 * ## The two trees
 *
 * A tag can be applied to a node and still be invisible to an accessibility layer,
 * because the node merges into an ancestor. So the two claims are separate:
 *
 * - `awaitTag(tag)` — the **merged** tree, the one an accessibility layer reads.
 *   This is the claim the device tier makes with `findObject(By.res(tag))`.
 * - `tagInventory()` — the **raw** tree, the walk this test's second case uses to
 *   show that the inventory reports what is there and not what was asked for.
 *
 * @see docs/testing/what-each-tier-proves.md
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class ShellTestTagReachabilityTest {

    /**
     * The shell rendered, and every tag the shared UI applies is reachable in the
     * merged semantics tree.
     *
     * "Reachable in the merged tree" is the same claim the device tier makes with
     * `findObject(By.res(tag))`, one step before Android turns the tag into a
     * resource id. A tag that stops being applied, or that moves to a node that
     * merges away, fails here in seconds instead of on the emulator.
     */
    @Test
    fun the_shell_renders_and_its_test_tags_reach_the_semantics_tree() =
        runDesktopAppTest(clock = CLOCK) { koin ->
            tasks(koin).given(due = TODAY, title = TASK_TITLE)

            // The shell rendered. Its chrome carries no test tag on this tier, so it
            // is addressed the way the desktop tier addresses it: by content
            // description. Both controls are always on screen — the hamburger sits
            // top-left, the FAB bottom-right — so neither can be below the fold.
            assertContentDescriptionDisplayed(DesktopShell.HAMBURGER)
            assertContentDescriptionDisplayed(DesktopShell.FAB_ADD_TASK)
            assertCurrentTab("Today")

            // The tags reached the tree.
            SHELL_TAGS.forEach { tag ->
                awaitTag(tag).assertExists()
            }
        }

    /**
     * The walk reports what is in the tree — the tags it finds, and the ones it
     * does not.
     *
     * This is the control for the case above. Without it, an inventory that came
     * back empty (a tree that never mounted, a walk that lost its roots) would make
     * every positive assertion in this file pass for the wrong reason. And the
     * negative half pins the tier split in code: the Android bottom bar's tag is
     * applied in `shared/src/androidMain`, so it cannot be here — this tier has a
     * drawer, not a bottom bar.
     */
    @Test
    fun the_tag_walk_finds_the_shared_tags_and_not_the_android_only_ones() =
        runDesktopAppTest(clock = CLOCK) { koin ->
            tasks(koin).given(due = TODAY, title = TASK_TITLE)

            // The seeded row reaches the tree through a repository write and a
            // re-emit, so the walk has to wait for it — otherwise it measures the
            // write's latency instead of the tree.
            SHELL_TAGS.forEach { tag ->
                awaitTag(tag).assertExists()
            }

            val inventory = tagInventory()
            assertTrue(
                inventory.isNotEmpty(),
                "The walk found no tags at all — the tree it walked is empty, so every " +
                    "assertion below would pass for the wrong reason.",
            )

            SHELL_TAGS.forEach { tag ->
                assertTrue(
                    tag in inventory,
                    "Expected '$tag' in the semantics tree, but the walk found: " +
                        inventory.sorted().joinToString(", "),
                )
            }

            assertTrue(
                TestTags.navTab("Today") !in inventory,
                "The Android bottom bar's tag is in the desktop tree. The desktop shell " +
                    "has a drawer, and the bottom bar lives in shared/src/androidMain — " +
                    "so this is either a platform mix-up or a stale expectation.",
            )
        }

    private companion object {
        /** Mid-month, so no assertion in this file straddles a date boundary. */
        val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK: FakeClock = FakeClock(FIXED_NOW)
        val TODAY: LocalDate = LocalDate(2026, 9, 16)

        /** The seeded task — dated, so the agenda's Today section renders it. */
        const val TASK_TITLE = "Buy milk"

        /**
         * The tags the shared UI applies on the screen the shell opens.
         *
         * Two of them are constant (the agenda top bar's actions) and two are
         * derived from the task's title (`slug`), so the list covers both a static
         * and a computed tag — the computed one is the one that breaks when a
         * title is renamed, a locale changes, or a row is rebuilt.
         */
        val SHELL_TAGS: List<String> = listOf(
            TestTags.AGENDA_SAVED_VIEWS_BUTTON,
            TestTags.AGENDA_SAVE_CURRENT_BUTTON,
            TestTags.taskItem(TASK_TITLE),
            TestTags.taskCheckbox(TASK_TITLE),
        )
    }
}
