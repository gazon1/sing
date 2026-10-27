package com.singularity.todo.test.helpers

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import kotlin.test.assertEquals

/** Generous upper bound; real transitions settle in well under a second. */
internal const val TIMEOUT_MS = 5_000L

/**
 * The same question `assertIsDisplayed` answers, as a predicate over a fetched
 * node: non-empty bounds that intersect the bounds of the node's own semantics
 * root.
 *
 * "Own root", not `onRoot()`: a dialog or popup on desktop is a separate
 * window with its own semantics root, and its node can exist in the tree while
 * that window has not been laid out yet — bounds 0×0, nothing to intersect.
 * That is the state behind the desktop-suite flake fixed by #569: `waitUntil`
 * finds the node (existence), `assertIsDisplayed` immediately after fails
 * (display), and the two never agreed on a loaded headless runner.
 *
 * A node below the fold in a `verticalScroll` screen also has real bounds —
 * off-screen, not zero — so this predicate does not interfere with
 * `awaitTag` + `scrollToTag` flows, which is why the presence wait and the
 * display wait stay separate helpers rather than one strengthened wait.
 */
private fun SemanticsNode.isDisplayedInItsRoot(): Boolean {
    val bounds = boundsInRoot
    if (bounds.width <= 0f || bounds.height <= 0f) return false
    var root = this
    while (true) {
        root = root.parent ?: break
    }
    val rootBounds = root.boundsInRoot
    return bounds.left < rootBounds.right && bounds.right > rootBounds.left &&
        bounds.top < rootBounds.bottom && bounds.bottom > rootBounds.top
}

/**
 * Waits until some node matching [matcher] is displayed ([isDisplayedInItsRoot]).
 * The counterpart of [awaitTag] for assertions that need the node on screen, not
 * merely composed.
 */
@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.awaitDisplayed(
    matcher: SemanticsMatcher,
    description: String,
    timeoutMs: Long = TIMEOUT_MS,
) = waitUntil(
    conditionDescription = description,
    timeoutMillis = timeoutMs,
) {
    onAllNodes(matcher).fetchSemanticsNodes().any { it.isDisplayedInItsRoot() }
}

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
                // Collect all tags on every poll cycle so the list is fresh when
                // the timeout fires.
                //
                // Every node that *has* a test tag, not a node whose tag is the
                // literal "*": `onAllNodesWithTag` matches the tag value exactly, so
                // "*" matched nothing and the explanation always listed zero tags —
                // the one diagnostic that was supposed to name the nearest
                // alternative named none.
                allTags.clear()
                onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag))
                    .fetchSemanticsNodes()
                    .forEach { node ->
                        node.config.getOrNull(SemanticsProperties.TestTag)?.let { allTags.add(it) }
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
    awaitDisplayed(
        matcher = hasTestTag(tag),
        description = "some node with testTag '$tag' is on screen",
    )
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
 * Asserts that a node with [text] is actually on screen.
 *
 * Waits for *display*, not existence: a node can be in the semantics tree
 * before its window or layout pass has produced bounds (#569 — "found by
 * waitUntil, failed by assertIsDisplayed"). Standalone or after [awaitText];
 * both are race-free because the wait is part of this call.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTextDisplayed(text: String) {
    awaitDisplayed(
        matcher = hasText(text),
        description = "node with text '$text' is displayed",
    )
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
 * Whether the checkbox with [testTag] reports ToggleableState.On right now.
 *
 * The negative of [awaitCheckboxChecked]: that one waits for the state to
 * become on, this one answers without waiting. A checkbox has no "is ticked"
 * property on the node — its state is the `ToggleableState` the toggleable
 * modifier writes — so the answer is read from the semantics of every node
 * carrying the tag.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.isCheckboxChecked(testTag: String): Boolean =
    onAllNodesWithTag(testTag)
        .fetchSemanticsNodes()
        .any { node ->
            node.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
        }

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
 * Waits for a node with [tag] and asserts it is *displayed*, not merely composed.
 *
 * Two waits, on purpose. [awaitTag] is existence — and it must stay existence,
 * because `clickTagScrolled` relies on finding a below-the-fold node before
 * scrolling to it. Display is the second wait, and it is what an assertion of
 * "is displayed" needs: existence alone let a popup or dialog node through in
 * the window before layout, and `assertIsDisplayed` failed on it immediately
 * after a successful `waitUntil` (#569). The timeout message names the phase,
 * so a failure says which of the two never happened.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTagDisplayed(tag: String) = step("assertTagDisplayed", tag) {
    awaitTag(tag)
    try {
        awaitDisplayed(
            matcher = hasTestTag(tag),
            description = "node with testTag '$tag' is displayed",
        )
    } catch (e: Throwable) {
        throw AssertionError(
            "Node with testTag '$tag' is in the semantics tree but never became " +
                "displayed within $TIMEOUT_MS ms — present is not shown. If it is " +
                "below the fold, scrollToTag it first; if it is in a dialog or " +
                "popup, the window did not lay out in time (check the semantics " +
                "tree in the suppressed chain).",
        ).also { it.addSuppressed(e) }
    }
    onNodeWithTag(tag).assertIsDisplayed()
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
 * Waits for the node with [tag] and asserts it is the selected one.
 *
 * A control that shows which of its options is in effect carries that in its
 * semantics (`Selected`). Reading it is how a test says "the grouping on screen
 * is the one the user chose" rather than "the control was pressed" — the second
 * claim is about the click, the first about what the screen now shows.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTagSelected(tag: String) = step("assertTagSelected", tag) {
    awaitTag(tag).assertIsSelected()
}

/**
 * The unclipped bounds of the node with [tag], in root coordinates.
 *
 * Order assertions need a number, not a handle: "the child sits to the right of
 * its parent" and "today's section is above tomorrow's" are statements about
 * *where* two nodes are, and the bounds are the only place that says so.
 *
 * Unclipped, not clipped: a node below the fold still has a position, and the
 * comparison a tree or a timeline makes is about the arrangement, not about what
 * happens to fit on the screen.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.unclippedBoundsOfTag(tag: String): DpRect =
    awaitTag(tag).getUnclippedBoundsInRoot()

/**
 * One-shot assertion that no node with [text] exists. Same rule as
 * [assertTagNotExists]: only for nodes that were never there.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTextNotExists(text: String) {
    onNodeWithText(text).assertDoesNotExist()
}

/**
 * Waits for the node with [desc] content description to be *displayed* and
 * asserts it. Same existence-vs-display split as [assertTagDisplayed] (#569).
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertContentDescriptionDisplayed(desc: String) {
    awaitContentDescription(desc)
    awaitDisplayed(
        matcher = hasContentDescription(desc),
        description = "node with contentDescription '$desc' is displayed",
    )
    onNodeWithContentDescription(desc).assertIsDisplayed()
}

/**
 * [assertContentDescriptionDisplayed] for a node that may sit below the fold of a
 * scrollable container: scrolls it into view first, then asserts.
 *
 * The difference is the whole point. "Displayed" is a claim that the user can see the
 * node *right now*; in a scrolling column that is false for the trailing entries until
 * someone scrolls, and true afterwards. "Reachable" is the claim a navigation test
 * actually wants, and it is the one that keeps failing if the container stops scrolling.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertContentDescriptionReachable(desc: String) {
    awaitContentDescription(desc).performScrollTo()
    awaitDisplayed(
        matcher = hasContentDescription(desc),
        description = "node with contentDescription '$desc' is displayed after scrolling",
    )
}

/**
 * The generic display-wait: waits until the first node matching [matcher] is
 * *displayed*, then asserts it. Use for composite matchers no tag/text/cdesc
 * helper covers — e.g. `hasText("Edit") and hasClickAction()` — so that a
 * "present but not yet laid out" node cannot fail a one-shot
 * `assertIsDisplayed` (#569). [description] names the target in wait and
 * failure messages.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertNodeDisplayed(matcher: SemanticsMatcher, description: String) {
    awaitDisplayed(
        matcher = matcher,
        description = "node matching '$description' is displayed",
    )
    onAllNodes(matcher).onFirst().assertIsDisplayed()
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
 * Waits for a node with [text] and asserts it is enabled.
 *
 * Mirrors [assertTagEnabled] for controls that are addressed by their visible
 * label rather than a testTag (dialog buttons such as "Save" / "Rename").
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTextEnabled(text: String) {
    awaitText(text).assertIsEnabled()
}

/**
 * Waits for a node with [text] and asserts it is NOT enabled.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTextNotEnabled(text: String) {
    awaitText(text).assertIsNotEnabled()
}

/**
 * [assertTextNotEnabled] for a *button* whose label is [text].
 *
 * The two are not interchangeable. `assertTextNotEnabled("Rename")` matches the `Text`
 * inside the button, and that node carries no `Disabled` property — `enabled = false` on
 * a Material3 `TextButton` decorates the button node, not its label. So the text-level
 * assertion fails on a correctly disabled control, and passes on a button that was never
 * disabled at all: it says nothing about the thing under test.
 *
 * `hasClickAction()` selects the control rather than its label. A disabled
 * `Modifier.clickable` keeps its click semantics and adds `Disabled`, so this matcher
 * still resolves while the control is off — and `assertIsEnabled`/`assertIsNotEnabled`
 * then read the property that actually describes the button.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertButtonNotEnabled(text: String) {
    awaitDisplayed(
        matcher = hasText(text) and hasClickAction(),
        description = "button labelled '$text' is displayed",
    )
    onNode(hasText(text) and hasClickAction()).assertIsNotEnabled()
}

/** [assertButtonNotEnabled] for a button that must be enabled. */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertButtonEnabled(text: String) {
    awaitDisplayed(
        matcher = hasText(text) and hasClickAction(),
        description = "button labelled '$text' is displayed",
    )
    onNode(hasText(text) and hasClickAction()).assertIsEnabled()
}

/**
 * Waits for the node with [tag] and asserts its text equals [expected].
 *
 * An **editable** node — a text field — is compared through its `EditableText`, not
 * through the node's plain text. A state-based text field keeps its value in
 * `EditableText` and carries no `Text` of its own, but its decoration does: the label
 * is a `Text` composable that is composed whether or not the field holds anything, and
 * the placeholder is one too. Those land in the same merged node, so the plain text of
 * a labelled field is the label, and Compose's `assertTextEquals` — which requires the
 * node's *whole* text list to match — fails on a field whose value is exactly right.
 *
 * Reading `EditableText` directly is what makes the assertion mean "the field holds
 * this text" instead of "the field and its decoration, taken together, hold exactly
 * this text".
 *
 * A node that is not editable has no `EditableText`, and for those the plain text is
 * the only text there is: the assertion falls back to Compose's own.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTagTextEquals(tag: String, expected: String) {
    val node = awaitTag(tag).fetchSemanticsNode()
    val editable = node.config.getOrNull(SemanticsProperties.EditableText)
    if (editable == null) {
        awaitTag(tag).assertTextEquals(expected)
        return
    }
    assertEquals(
        expected,
        editable.text,
        "the text held by the editable node with testTag '$tag'",
    )
}

/**
 * Waits until the node with [tag] holds exactly [expected], then returns.
 *
 * The wait counterpart of [assertTagTextEquals], and the one a test wants when the
 * text is changed by something *other* than the test's own input: a ViewModel that
 * empties its copy of a field, a screen that rewrites a title. The field holds the
 * new text a frame or two later, so a one-shot assertion races the very transition
 * it is about.
 *
 * @param tag the node to watch.
 * @param expected the text the node must end up holding.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.awaitTagTextEquals(tag: String, expected: String) = step("awaitTagTextEquals", tag) {
    waitUntil(
        conditionDescription = "node with testTag '$tag' holds the text '$expected'",
        timeoutMillis = TIMEOUT_MS,
    ) {
        val node = runCatching { awaitTag(tag).fetchSemanticsNode() }.getOrNull()
        val editable = node?.config?.getOrNull(SemanticsProperties.EditableText)
        if (editable != null) {
            editable.text == expected
        } else {
            node?.config?.getOrNull(SemanticsProperties.Text)?.any { it.text == expected } == true
        }
    }
}

/**
 * One-shot assertion that a node with [tag] exists (displayed or not).
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTagExists(tag: String) {
    onNodeWithTag(tag).assertExists()
}

/**
 * One-shot assertion that a node with [tag] exists in the *unmerged* semantics
 * tree.
 *
 * Needed when the tag lives on a child whose semantics are not merged up into
 * an ancestor — the default merged-tree lookup would not find it. Used by the
 * composable-level card tests that assert a card rendered without driving the
 * whole app harness.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.assertTagExistsOnUnmergedTree(tag: String) {
    onNodeWithTag(tag, useUnmergedTree = true).assertExists()
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
    mainRootNodeCount()
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
