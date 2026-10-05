package com.singularity.todo.test.helpers

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString

/** Generous upper bound; real transitions settle in well under a second. */
internal const val TIMEOUT_MS = 5_000L

private val TAG_PATTERN = Regex("""testTag=[^\s,\]]+""")

/**
 * Waits until a node with [tag] exists, then returns a handle to it.
 *
 * Saving is asynchronous — the editor writes through a repository scope and the
 * list re-emits from a Room flow — so a click issued straight after a save
 * button lands before the row is in the tree and fails with "could not find any
 * node". Use this instead of a bare `onNodeWithTag` after any write.
 *
 * On timeout, the error message includes a listing of the nearest available tags
 * via [explainMissingTag], so the failure is actionable without consulting the
 * full semantics dump.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.awaitTag(tag: String): SemanticsNodeInteraction = step("awaitTag", tag) {
    val allTags = mutableListOf<String>()
    var pollCount = 0
    val startTime = System.currentTimeMillis()
    try {
        waitUntil(
            conditionDescription = "node with testTag '$tag' appears",
            timeoutMillis = TIMEOUT_MS,
        ) {
            pollCount++
            val found = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            if (!found) {
                // Collect all nodes on every poll cycle so the list is fresh when
                // the timeout fires.
                onAllNodesWithTag("*").fetchSemanticsNodes().forEach { node ->
                    TAG_PATTERN.findAll(node.toString()).forEach { match ->
                        allTags.add(match.value.removePrefix("testTag="))
                    }
                }
            }
            found
        }
    } catch (e: Throwable) {
        // Tag-explainer fires on timeout
        val explanation = explainMissingTag(allTags.distinct().sorted(), tag)
        val elapsed = System.currentTimeMillis() - startTime
        val msg = buildString {
            appendLine(explanation)
            appendLine("Poll count: $pollCount. Elapsed: ${elapsed}ms. Timeout: ${TIMEOUT_MS}ms.")
            if (e.message != null) appendLine("Last exception: ${e::class.simpleName}: ${e.message}")
        }
        throw AssertionError(msg).also { it.addSuppressed(e) }
    }
    onNodeWithTag(tag)
}

/**
 * Waits until at least one node with [tag] is actually on screen, then returns.
 *
 * For a node that exists in exactly one copy, `awaitTag` is enough. This is for
 * pagers and lists that compose the same tag several times, some of them off
 * screen: HorizontalPager keeps the neighbouring pages composed, so
 * `onAllNodesWithTag(tag)[0]` is composition order, not what the user sees, and
 * the ordering can flip with the calendar date (a month-edge day pads into the
 * neighbouring page). "Some matching node is visible" is the assertion the test
 * means; an index is an implementation detail.
 *
 * Visibility is a non-empty intersection of the node's bounds with the root's —
 * the same thing `assertIsDisplayed` checks, applied per node instead of to one
 * indexed pick.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.awaitAnyDisplayed(tag: String) = step("awaitAnyDisplayed", tag) {
    val rootBounds = onRoot(useUnmergedTree = false).fetchSemanticsNode().boundsInRoot
    waitUntil(
        conditionDescription = "some node with testTag '$tag' is on screen",
        timeoutMillis = TIMEOUT_MS,
    ) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().any { node ->
            val b = node.boundsInRoot
            b.width > 0f && b.height > 0f &&
                b.left < rootBounds.right && b.right > rootBounds.left &&
                b.top < rootBounds.bottom && b.bottom > rootBounds.top
        }
    }
}

/**
 * Waits until a node matching [matcher] exists, then returns a handle to it.
 *
 * Use this overload when the selector is not a testTag (e.g. `hasContentDescription(...)`,
 * `hasText(...)`, `hasAnyAncestor(...)`). For testTag-based selectors prefer the
 * string overload — it provides better error messages via [explainMissingTag].
 *
 * @param matcher The semantics matcher to wait for.
 * @param timeoutMs Overrides the default [TIMEOUT_MS]. Pass [TIMEOUT_MS] to use
 *                  the shared constant.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.awaitTag(
    matcher: SemanticsMatcher,
    timeoutMs: Long = TIMEOUT_MS,
): SemanticsNodeInteraction = step("awaitTag(matcher)", matcher.description) {
    try {
        waitUntil(
            conditionDescription = "node matching '${matcher.description}' appears",
            timeoutMillis = timeoutMs,
        ) {
            onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    } catch (e: Throwable) {
        throw AssertionError(
            "Node matching '${matcher.description}' not found or not unique after ${timeoutMs} ms",
        ).also { it.addSuppressed(e) }
    }
    onNode(matcher)
}

/**
 * Waits until no node with [tag] exists, then returns — the counterpart to
 * [awaitTag] for asserting that something is *gone*.
 *
 * `assertDoesNotExist` checks once, right after Compose's auto-sync, which is
 * not the same as "it is gone": navigation commits asynchronously (the outgoing
 * screen stays composed until the incoming one's state resolves), so a one-shot
 * check races the transition and fails intermittently under machine load. Use
 * this whenever the thing being asserted absent is disappearing *because of*
 * something the test just did.
 *
 * Do not use it to assert absence of something that was never there — that is a
 * plain `assertDoesNotExist` and needs no waiting.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.awaitTagGone(tag: String) = step("awaitTagGone", tag) {
    waitUntil(
        conditionDescription = "no node with testTag '$tag' remains",
        timeoutMillis = TIMEOUT_MS,
    ) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
    }
}

/**
 * Waits for a node with [text] to appear, then returns a handle to it.
 * Uses `hasText()` semantics matcher — matches any node whose semantics text
 * includes the given string.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.awaitText(text: String) = step("awaitText", text) {
    waitUntil(
        conditionDescription = "node with text '$text' appears",
        timeoutMillis = TIMEOUT_MS,
    ) {
        onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithText(text, useUnmergedTree = true)
}

/**
 * Asserts that a node with [text] is displayed. Use after [awaitText] or standalone
 * when the node is expected to already exist.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTextDisplayed(text: String) {
    onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
}

/**
 * Waits for a node with contentDescription [desc] to appear, then returns a handle.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.awaitContentDescription(desc: String) = step("awaitContentDescription", desc) {
    waitUntil(
        conditionDescription = "node with contentDescription '$desc' appears",
        timeoutMillis = TIMEOUT_MS,
    ) {
        onAllNodes(hasContentDescription(desc)).fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithContentDescription(desc)
}

/**
 * Returns the count of nodes with [tag] in the current semantics tree.
 * Does not wait — captures the count at call time.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.countNodes(tag: String): Int =
    onAllNodesWithTag(tag).fetchSemanticsNodes().size

/**
 * Waits until the checkbox with [testTag] reports ToggleableState.On.
 * Use after clickCheckbox to assert the state change landed.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.awaitCheckboxChecked(testTag: String) = step("awaitCheckboxChecked", testTag) {
    waitUntil(
        conditionDescription = "checkbox '$testTag' is checked",
        timeoutMillis = TIMEOUT_MS,
    ) {
        onAllNodesWithTag(testTag)
            .fetchSemanticsNodes()
            .any { node ->
                val state = node.config.getOrNull(SemanticsProperties.ToggleableState)
                state == androidx.compose.ui.state.ToggleableState.On
            }
    }
}

/**
 * Waits for a node with [tag] and asserts it is displayed.
 * The wait matters: the node may still be composing when the test reaches it.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTagDisplayed(tag: String) {
    awaitTag(tag).assertIsDisplayed()
}

/**
 * One-shot assertion that no node with [tag] exists. Use for things that were
 * never in the tree; for things that just disappeared, use [awaitTagGone].
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTagNotExists(tag: String) {
    onNodeWithTag(tag).assertDoesNotExist()
}

/**
 * One-shot assertion that no node with [text] exists. Same rule as
 * [assertTagNotExists]: only for nodes that were never there.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTextNotExists(text: String) {
    onNodeWithText(text).assertDoesNotExist()
}

/**
 * Waits for a node with [desc] content description and asserts it is displayed.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertContentDescriptionDisplayed(desc: String) {
    awaitContentDescription(desc).assertIsDisplayed()
}

/**
 * One-shot assertion that no node with contentDescription [desc] exists.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertContentDescriptionNotExists(desc: String) {
    onNodeWithContentDescription(desc).assertDoesNotExist()
}

/**
 * Waits for the node with [tag] and asserts it is enabled.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTagEnabled(tag: String) {
    awaitTag(tag).assertIsEnabled()
}

/**
 * Waits for the node with [tag] and asserts it is NOT enabled.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTagNotEnabled(tag: String) {
    awaitTag(tag).assertIsNotEnabled()
}

/**
 * Waits for the node with [tag] and asserts its text equals [expected].
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTagTextEquals(tag: String, expected: String) {
    awaitTag(tag).assertTextEquals(expected)
}

/**
 * One-shot assertion that a node with [tag] exists (displayed or not).
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTagExists(tag: String) {
    onNodeWithTag(tag).assertExists()
}

/**
 * Produces a human-readable explanation of why a `testTag` was not found in the
 * current semantics tree, and what tags are available.
 *
 * Used to enrich [AssertionError] messages from [awaitTag] so that a missing tag
 * failure names the nearest available alternative rather than just the one that
 * was not found.
 *
 * Pure: no Compose state, no I/O, fully deterministic.
 *
 * @param availableTags All `testTag` values present in the current semantics tree.
 * @param wantedTag     The tag the test was looking for.
 */
fun explainMissingTag(availableTags: List<String>, wantedTag: String): String {
    val nearby = availableTags
        .filter { it.contains(wantedTag.take(4), ignoreCase = true) }
        .take(3)
    return buildString {
        appendLine("Tag '$wantedTag' is not in the semantics tree.")
        if (nearby.isNotEmpty()) {
            appendLine("Nearby tags: ${nearby.joinToString { "'$it'" }}")
        }
        appendLine("All available tags (${availableTags.size} total):")
        availableTags.take(20).forEach { appendLine("  $it") }
        if (availableTags.size > 20) appendLine("  ... and ${availableTags.size - 20} more")
    }
}

/**
 * A stable signature of the main window's semantics tree.
 *
 * Used by `SheetReachabilityTest` to assert that opening a `ModalBottomSheet`
 * changes *nothing* here. The comparison is by total node count rather than by
 * tag set, because a `Popup`-based sheet would still add nodes without adding a
 * tag — and that is exactly the case where the tier rule in
 * `Maestro/CONVENTIONS.md` would need to be revisited.
 *
 * Exists as a helper rather than inline in the test because `HarnessConventionTest`
 * requires raw selector calls to live here, and because a second caller would
 * otherwise re-derive the same `useUnmergedTree = true` choice.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.mainTreeSize(): Int = step("mainTreeSize") {
    onRoot(useUnmergedTree = true).printToString(maxDepth = 60).split("Node #").size - 1
}

/**
 * How many nodes carry [tag] **across every semantics root**, not just the main
 * window.
 *
 * This is the search that a `ModalBottomSheet` on desktop is invisible to, and
 * the reason the tier rule exists: on skiko the sheet resolves to a `Dialog`,
 * which is a separate window, so `atLeastOneRootRequired = false` finds nothing
 * either. Returning the count rather than asserting lets a test state the fact
 * and attach its own message — the failure needs to point at the convention, not
 * at a missing node.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.countNodesWithTagInAnyRoot(tag: String): Int = step("countInAnyRoot", tag) {
    onAllNodesWithTag(tag).fetchSemanticsNodes(false).size
}
