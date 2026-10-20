package com.singularity.todo.test.helpers

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi

/**
 * Every `testTag` the current semantics tree carries, as the **raw** tree reports it.
 *
 * This is the desktop counterpart of the accessibility-hierarchy dump the device
 * tier takes in `ShellProbe.dumpHierarchy()`: one walk of the tree, one entry per
 * tagged node. The device walk reads `resource-id` attributes — the ids Android's
 * `testTagsAsResourceId` derives from a Compose test tag. This walk reads the tags
 * themselves, which is the same information one step earlier in the pipeline.
 *
 * The walk is the *raw* tree, so a tag on a node that merges into an ancestor is
 * reported here even when the merged tree does not expose it. For the claim the
 * accessibility layer makes — "the tag is reachable in the merged tree" — use the
 * merged lookup (`awaitTag(tag)`, `assertTagExists(tag)`) instead.
 *
 * Overlays (dialogs, popups) create additional semantics roots on desktop; the walk
 * tolerates them rather than assuming a single root.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.tagInventory(): Set<String> =
    collectTaggedNodes().map { it.second }.toSet()
