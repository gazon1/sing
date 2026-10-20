package com.singularity.todo.test.helpers

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag

/**
 * Every `testTag` the **merged** semantics tree carries, across every root.
 *
 * The merged tree is the accessibility tree: a node that Compose folded into an
 * ancestor is not announced separately, and — because `TestTag`'s merge policy
 * keeps the *parent's* value — a tag that sits below a merging node (a Material3
 * `Card`/`Surface` merges its descendants) does not survive the merge at all.
 *
 * That is why the unmerged tree is the wrong place to look for "can an
 * end-to-end test address this?": the unmerged tree shows tags that no
 * accessibility-driven tool can ever see.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.mergedTreeTags(): Set<String> {
    val tags = sortedSetOf<String>()
    mergedRoots().forEach { root ->
        root.visit { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.let { tags.add(it) }
        }
    }
    return tags
}

/**
 * Asserts that [tag] is addressable in the **merged** semantics tree.
 *
 * This is the check an end-to-end flow needs: Maestro drives the app through the
 * accessibility tree, so a tag that only exists in the unmerged tree is a tag
 * the flow cannot use. A `testTag` on a node inside a merging `Card` is exactly
 * that — present in the tree, invisible to the flow.
 *
 * The failure message says which of the two cases it is:
 *
 * - the tag is in the unmerged tree but not the merged one → it sits under a
 *   node that merges its descendants, and has to move up to the item's outer
 *   node to become addressable;
 * - the tag is nowhere → it is simply not rendered (or rendered off-screen with
 *   a different tag).
 *
 * @param tag the tag the flow addresses.
 * @param screen the screen the tag is expected on — named in the failure
 *   message, because "the tag is missing" is only actionable next to the screen
 *   that was supposed to show it.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTagInMergedTree(tag: String, screen: String) =
    step("assertTagInMergedTree", "$screen/$tag") {
        val found = runCatching {
            waitUntil(
                conditionDescription = "node with testTag '$tag' is in the merged tree of $screen",
                timeoutMillis = TIMEOUT_MS,
            ) {
                onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        }
        if (found.isSuccess) return@step

        val merged = mergedTreeTags()
        val unmerged = unmergedTreeTags()
        val diagnosis = if (tag in unmerged) {
            "The tag IS in the tree, but only in the unmerged one: it sits below a " +
                "node that merges its descendants (a Material3 Card/Surface merges " +
                "its children, and TestTag's merge policy keeps the parent's value). " +
                "An end-to-end test driving the accessibility tree cannot address " +
                "it — move the tag to the item's outer node."
        } else {
            "The tag is in neither tree — nothing rendered it (or it rendered under " +
                "a different tag)."
        }
        throw AssertionError(
            "testTag '$tag' is not addressable in the merged semantics tree of " +
                "$screen. $diagnosis\n" +
                "Tags in the merged tree (${merged.size}): " +
                "${merged.joinToString(", ")}\n" +
                "Tags in the unmerged tree only (${unmerged - merged}): " +
                "${(unmerged - merged).joinToString(", ")}",
        ).also { it.addSuppressed(found.exceptionOrNull()!!) }
    }

/**
 * Every `testTag` the **unmerged** semantics tree carries, across every root.
 *
 * The counterpart of [mergedTreeTags], used by [assertTagInMergedTree] to tell
 * "the tag is nowhere" apart from "the tag is there but merged away".
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.unmergedTreeTags(): Set<String> {
    val tags = sortedSetOf<String>()
    semanticsRoots().forEach { root ->
        root.visit { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.let { tags.add(it) }
        }
    }
    return tags
}
