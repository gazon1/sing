package com.singularity.todo.test.helpers

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect

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
fun DesktopComposeUiTest.mainRootNodeCount(): Int = semanticsRoots().firstOrNull()?.countNodes() ?: 0

/**
 * The number of nodes carrying [tag] in the app's **main** window — the first root.
 *
 * The counterpart of [countNodesWithTagInAnyRoot] for the window that a sheet must
 * *not* touch: a sheet's content is in its own root, so this counts nothing while a
 * sheet is open.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.countNodesWithTagInMainRoot(tag: String): Int =
    semanticsRoots().firstOrNull()?.countNodesWithTag(tag) ?: 0

/**
 * The number of nodes in each root of the scene, in tree order.
 *
 * The per-root sizes are what tell a popup's own window apart from the app's:
 * a root that grows while a menu is open is the menu's window, and a root that
 * stays at zero is a window that was opened and closed.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.rootSizes(): List<Int> = semanticsRoots().map { it.countNodes() }

/**
 * The bounds of the app's main window, in root coordinates.
 *
 * The first root is the app's own window (see [semanticsRoots]), and its
 * bounds are the edges a layout ends at — "the last row ends 8 dp above the
 * bottom of the screen" is a statement about them. The bounds are in dp, like
 * every other bound the helpers hand out, so they compare with
 * `unclippedBoundsOfTag` without a conversion.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.mainRootBounds(): DpRect =
    onAllNodes(isRoot(), useUnmergedTree = true)[0].getUnclippedBoundsInRoot()

/**
 * The space a node leaves below itself inside the root it lives in.
 *
 * A `Popup` is its own semantics root, drawn above the app's window: the root
 * of a node inside a popup is that popup's root, and its bottom edge is where
 * the popup's content ends. The space between the node and that edge is what a
 * bottom padding inside the popup reserved — the clearance a shell's floating
 * action button asks for.
 *
 * Measured on the node's own root, not on the app's window: a toast in a popup
 * has to be measured against the root it is drawn in, or the answer says
 * nothing about the popup.
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.spaceBelowTagInItsRoot(tag: String): Dp {
    val roots = onAllNodes(isRoot(), useUnmergedTree = true)
    val rootNodes = roots.fetchSemanticsNodes()
    val node =
        onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull() ?: error("no node with testTag '$tag' in the scene")
    val root = generateSequence(node) { it.parent }.last()
    val index = rootNodes.indexOfFirst { it.id == root.id }
    if (index < 0) error("the node's root is not among the scene's roots")
    val rootBounds = roots[index].getUnclippedBoundsInRoot()
    val nodeBounds = onAllNodesWithTag(tag, useUnmergedTree = true)[0].getUnclippedBoundsInRoot()
    return rootBounds.bottom - nodeBounds.bottom
}

/** This node and every node below it. */
private fun SemanticsNode.countNodes(): Int = 1 + children.sumOf { it.countNodes() }

/** This node and every node below it that carries [tag]. */
private fun SemanticsNode.countNodesWithTag(tag: String): Int =
    (if (config.getOrNull(SemanticsProperties.TestTag) == tag) 1 else 0) +
        children.sumOf { it.countNodesWithTag(tag) }
