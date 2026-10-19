package com.singularity.todo.test.helpers

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi

/**
 * Accessibility check over the merged semantics tree: every clickable node must
 * carry something a screen reader can announce.
 *
 * ## Why the merged tree
 *
 * Accessibility is a property of the tree a user agent actually consumes, which
 * is the merged one. The unmerged tree is for selector debugging — it shows the
 * raw nodes before Compose folds them, and a duplicate `Text` there is a normal
 * intermediate state, not a defect.
 *
 * ## What counts as a violation
 *
 * A node with a click action and none of: text, a content description, or a
 * testTag. The first two are what a screen reader announces. The third is
 * accepted because a testTag marks a node this project has deliberately made
 * addressable — it is not a substitute for a label for users, but treating it as
 * a label keeps the signal pointed at the *unmarked* affordances, which are the
 * ones nobody has looked at. Decorative icons with `contentDescription = null`
 * are not clickable, so they do not show up here and no allowlist is needed.
 *
 * The node also has to be **on screen**. A clickable that lies outside its
 * root's window — scrolled out of a row, parked off to the left with a closed
 * drawer, clipped below the fold of a sheet — is not something a screen reader
 * ever reaches, and reporting it teaches everyone to ignore the check. The
 * rule is generic, not a list of known offenders; see
 * `feature/flows/A11yVisibilityRuleTest.kt`.
 *
 * ## Why it lives in the harness
 *
 * A JUnit `AfterEachCallback` runs after the Compose test has torn its scene
 * down, so there is no tree left to read. The check has to run inside the test,
 * while the scene is alive — hence a parameter on [runDesktopAppTest] rather than
 * an extension point.
 *
 * It is opt-in per flow on purpose: switching it on everywhere would report the
 * whole backlog the moment it is enabled, which is how these checks get
 * abandoned. The debt shrinks as flows adopt it.
 */
@OptIn(ExperimentalTestApi::class)
class A11yChecker(private val test: DesktopComposeUiTest) {

    /**
     * Returns one line per unlabelled clickable node; empty when the tree is clean.
     *
     * Every root is scanned, not just the first: a `Popup` and an `AlertDialog`
     * each open their own root on desktop, and `onRoot` throws when there is
     * more than one — so the check used to report *nothing* on any screen with a
     * menu or a dialog open, which is exactly the screen where a missing label
     * is most likely.
     *
     * Only clickables that are **on screen** are reported: a node outside its
     * root's window is not on the screen, and no screen reader ever reaches it
     * (see [isVisibleIn]).
     */
    fun scan(): List<String> = scanScene().violations

    /**
     * Scans every merged root of the scene **as it is right now** and reports
     * what the scan saw, root by root.
     *
     * [scan] throws the roots away once it has counted the violations, which is
     * all the end-of-test pass needs. A scan that runs *while an overlay is open*
     * needs more: it has to prove that the overlay's own root was walked, and a
     * violation has to name the root it came from — "a clickable node announces
     * nothing" is not actionable until you know which window it is in.
     */
    fun scanScene(): A11yScan {
        val roots = test.mergedRoots()
        var offScreen = 0
        val perRoot = roots.mapIndexed { index, root ->
            val violations = mutableListOf<String>()
            val tags = sortedSetOf<String>()
            val texts = mutableListOf<String>()
            root.visit { node ->
                node.config.getOrNull(SemanticsProperties.TestTag)?.let { tags.add(it) }
                node.config.getOrNull(SemanticsProperties.Text)
                    ?.filter { it.text.isNotBlank() }
                    ?.forEach { texts.add(it.text) }
                if (!node.isUnlabelledClickable()) return@visit
                if (node.isVisibleIn(root)) {
                    violations += node.describe()
                } else {
                    offScreen++
                }
            }
            A11yRootScan(index = index, tags = tags, texts = texts, violations = violations)
        }
        return A11yScan(roots = perRoot, offScreenSkipped = offScreen)
    }
}

/**
 * What the scan found in one semantics root.
 *
 * @property index position of the root in the scene — 0 is the app window, 1 the
 *   first dialog or popup that opened its own root.
 * @property tags every `testTag` the root carries in the merged tree.
 * @property texts every non-blank text the root carries in the merged tree.
 * @property violations the unlabelled clickable nodes in this root.
 */
data class A11yRootScan(val index: Int, val tags: Set<String>, val texts: List<String>, val violations: List<String>)

/**
 * The result of one accessibility scan of the scene.
 *
 * @property roots one entry per semantics root the scan walked.
 * @property offScreenSkipped how many unlabelled clickables the scan skipped
 *   because they are not on the screen.
 */
data class A11yScan(val roots: List<A11yRootScan>, val offScreenSkipped: Int = 0) {

    /** How many roots the scan walked — 1 for a plain screen, 2 with a dialog or a popup. */
    val rootsScanned: Int get() = roots.size

    /** Every unlabelled clickable node in the scene, across all roots. */
    val violations: List<String> get() = roots.flatMap { it.violations }

    /** Every `testTag` the scan saw, across all roots. */
    val tagsSeen: Set<String> get() = roots.flatMapTo(mutableSetOf()) { it.tags }

    /** Every non-blank text the scan saw, across all roots. */
    val textsSeen: List<String> get() = roots.flatMap { it.texts }
}

/**
 * Runs the accessibility scan against the scene as it is right now.
 *
 * The end-of-test pass in [runDesktopAppTest] runs *after* the test body, by
 * which time a dialog or a menu has usually been dismissed again — and the
 * overlay's own root is exactly where an unlabelled close button hides. This
 * helper is callable from anywhere inside a test body, so the scan can be run
 * while the overlay is still open.
 *
 * It is a scan, not an assertion: the violations are returned, never thrown.
 * A test that finds one is expected to *report* it — silencing an accessibility
 * defect is worse than not looking.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.a11yScan(): A11yScan = A11yChecker(this).scanScene()

internal fun SemanticsNode.visit(visit: (SemanticsNode) -> Unit) {
    visit(this)
    children.forEach { it.visit(visit) }
}

/**
 * Whether this node is inside the visible area of [root].
 *
 * The root's own bounds are its window: a node that lies entirely outside that
 * rectangle is not on the screen. It was scrolled out of a row, or a closed
 * drawer parked off to the left, or a sheet row clipped below the fold — and no
 * screen reader ever reaches it, so reporting it is a false positive that
 * teaches everyone to ignore the check.
 *
 * A node that *overlaps* the window counts as visible, even when it is clipped
 * at the edge: the visible part of it is still on the screen and still
 * reachable.
 */
private fun SemanticsNode.isVisibleIn(root: SemanticsNode): Boolean {
    val window = root.boundsInRoot
    val bounds = boundsInRoot
    return bounds.left < window.right && window.left < bounds.right &&
        bounds.top < window.bottom && window.top < bounds.bottom
}

private fun SemanticsNode.isUnlabelledClickable(): Boolean {
    if (config.getOrNull(SemanticsActions.OnClick) == null) return false
    // An editable field is never an unnamed button. A screen reader announces it
    // as an edit box with its value, and its OnClick is the focus/selection
    // affordance Compose adds to every TextField — not a control with no name.
    // Without this exclusion every screen with an input fails the check.
    if (config.getOrNull(SemanticsProperties.EditableText) != null) return false
    if (config.getOrNull(SemanticsProperties.IsEditable) == true) return false
    return labelKind().isEmpty()
}

/**
 * Which of the accepted labels this node carries, or empty if it carries none.
 *
 * `onClickLabel` counts: it lands on the click *action*, not on
 * `ContentDescription`, but a screen reader announces it all the same. Reading
 * only Text/ContentDescription/TestTag reported every
 * `Modifier.clickable(onClickLabel = …)` as a violation, which is wrong.
 */
private fun SemanticsNode.labelKind(): String = when {
    config.getOrNull(SemanticsProperties.Text)?.any { it.isNotBlank() } == true -> "text"

    config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.isNotBlank() } == true ->
        "contentDescription"

    config.getOrNull(SemanticsActions.OnClick)?.label?.isNotBlank() == true -> "onClickLabel"

    else -> config.getOrNull(SemanticsProperties.TestTag)?.let { "testTag=$it" } ?: ""
}

private fun SemanticsNode.describe(): String {
    val role = config.getOrNull(SemanticsProperties.Role)?.let { " role=$it" }.orEmpty()
    val center = boundsInRoot.center
    val parentTag = generateSequence(this) { it.parent }
        .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
        .firstOrNull()
    val where = if (parentTag != null) " under testTag=$parentTag" else ""
    return "  - clickable at (${center.x}, ${center.y})$role$where" +
        " — no text, no contentDescription, no testTag" +
        "\n      " + subtreeHint()
}

/**
 * A short excerpt of the node's own semantics, so the failure says *which*
 * affordance is unlabelled rather than only where it sits. Coordinates alone
 * send you hunting through a layout file.
 */
private fun SemanticsNode.subtreeHint(): String = buildList {
    config.getOrNull(SemanticsProperties.Text)?.let { add("text=${it.joinToString(" / ")}") }
    config.getOrNull(SemanticsProperties.ContentDescription)?.let {
        add("contentDescription=${it.joinToString(" / ")}")
    }
    // Descendant text: a clickable wrapping a Text inherits nothing, but seeing
    // the label that *is* in the subtree is what tells you the fix is to move it
    // up rather than to invent a new string.
    children.forEach { child ->
        child.config.getOrNull(SemanticsProperties.Text)?.firstOrNull { it.isNotBlank() }
            ?.let { add("childText=\"$it\"") }
    }
}.joinToString(" ").ifEmpty { "<no text anywhere in the subtree>" }.take(200)
