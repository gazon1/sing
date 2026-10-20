package com.singularity.todo.test.helpers

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString

/**
 * The scene's semantics roots, in tree order.
 *
 * A scene can have more than one root: a `Popup` and a `Dialog` each open their
 * own window, and `onRoot` insists on exactly one — with two roots it throws
 * "Expected exactly '1' node but found '2'". Every helper that looks at the
 * tree has to decide what to do with that, and deciding it in one place is what
 * keeps the decision the same everywhere.
 *
 * The app's own window is the first root: it is created with the composition,
 * and a popup or a dialog opens its root afterwards.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.semanticsRoots(): List<SemanticsNode> =
    onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()

/**
 * The scene's semantics roots in the **merged** tree — what a screen reader sees.
 *
 * The merged tree is the accessibility tree: a node that Compose folded into an
 * ancestor is not announced separately, so a check that walks the unmerged tree
 * reports violations a user would never hear.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.mergedRoots(): List<SemanticsNode> =
    onAllNodes(isRoot(), useUnmergedTree = false).fetchSemanticsNodes()

/**
 * The scene's semantics tree as text — **every** root, not just the first.
 *
 * The unmerged tree is used: the point of reading a tree is to see the raw
 * nodes before Compose folds them, and the merged tree hides exactly the
 * duplicate `Text` that makes a selector fail on ambiguity.
 *
 * A scene with more than one root says so, and dumps each root under a header.
 * A diagnostic that explains nothing is worse than no diagnostic.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.semanticsTree(maxDepth: Int = 25): String {
    val roots = onAllNodes(isRoot(), useUnmergedTree = true)
    val count = roots.fetchSemanticsNodes().size
    return if (count == 1) {
        roots[0].printToString(maxDepth = maxDepth)
    } else {
        buildString {
            appendLine("The scene has $count roots; each is dumped below.")
            for (index in 0 until count) {
                appendLine("=== Root $index ===")
                appendLine(roots[index].printToString(maxDepth = maxDepth))
            }
        }
    }
}

/**
 * The number of nodes in the app's **main** window — the first root.
 *
 * A `ModalBottomSheet` opens its own window on skiko, so the main tree is the
 * one that must not change when a sheet opens. Counting every root would make
 * the sheet's own nodes part of the "main" tree and hide the very change the
 * caller is looking for.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.mainRootNodeCount(): Int =
    semanticsRoots().firstOrNull()?.countNodes() ?: 0

/** This node and every node below it. */
private fun SemanticsNode.countNodes(): Int = 1 + children.sumOf { it.countNodes() }
