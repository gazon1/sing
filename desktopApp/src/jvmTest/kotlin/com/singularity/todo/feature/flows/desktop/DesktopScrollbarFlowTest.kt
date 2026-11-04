package com.singularity.todo.feature.flows.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertExists
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.PlatformScrollbarSemantics
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.goBack
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.submitTag
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.typeIntoTag
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * A real screen carries the desktop scrollbar, not just the modifier on its own.
 *
 * `PlatformScrollbarTest` pins the mechanism on a container built by the test;
 * this is the other half — the call site. A screen that loses the modifier, or
 * that hands the bar a state the list does not scroll, still compiles, and only a
 * traversal of the screen shows it. The assertion reads the key
 * [PlatformScrollbarSemantics.listState] back off the unmerged tree, because a
 * `LazyColumn`'s own node does not surface through the merged one (the notes
 * flows document the same limit for its `testTag`).
 *
 * The notes list is the reference call site because it is the one whose
 * navigation a test already drives: create a note, save, go back — the recipe
 * `NotesFlowTagReachabilityFlowTest` uses. The other lists that carry the bar
 * (agenda, projects, project detail, calendar's time grid, saved views,
 * statistics) are listed in
 * `docs/decisions/2026-11-04-the-scrollbar-publishes-the-state-it-draws-from.md`
 * and take the same assertion once their navigation is in a test.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class DesktopScrollbarFlowTest {

    @Test
    fun the_notes_list_carries_the_desktop_scrollbar() =
        runDesktopAppTest(checkA11y = true) {
            tapTab("Notes")
            typeIntoTag(TestTags.NOTES_QUICK_ADD_INPUT, "Groceries")
            submitTag(TestTags.NOTES_QUICK_ADD_INPUT)
            // The editor autosaves; saving does not leave it, so leave explicitly.
            clickTag(TestTags.NOTE_EDITOR_SAVE)
            goBack()

            onNode(
                SemanticsMatcher("carries the desktop scrollbar") { node ->
                    node.config.getOrNull(PlatformScrollbarSemantics.listState) != null
                },
                useUnmergedTree = true,
            ).assertExists()
        }
}
