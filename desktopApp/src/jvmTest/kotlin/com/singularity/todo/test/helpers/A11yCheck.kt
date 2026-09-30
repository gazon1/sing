package com.singularity.todo.test.helpers

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot

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

    /** Returns one line per unlabelled clickable node; empty when the tree is clean. */
    fun scan(): List<String> {
        val root = runCatching { test.onRoot(useUnmergedTree = false).fetchSemanticsNode() }
            .getOrNull() ?: return emptyList()
        val violations = mutableListOf<String>()
        root.visit { node -> if (node.isUnlabelledClickable()) violations += node.describe() }
        return violations
    }
}

private fun SemanticsNode.visit(visit: (SemanticsNode) -> Unit) {
    visit(this)
    children.forEach { it.visit(visit) }
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
