package com.singularity.todo.test.helpers

import androidx.compose.ui.graphics.toAwtImage
import java.awt.Color as AwtColor
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import java.awt.BasicStroke
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * Draws bounding-box overlays onto a Compose screenshot.
 *
 * Every node that carries a `testTag` is rendered with a labeled rectangle:
 * - **Gray** frame and label for all tagged nodes
 * - **Red** frame and label for [highlightTag] and for the nearest candidates
 *   (prefix-match on the tag string)
 *
 * The annotated image is written to [file]. A text fallback [nodesFile] is always
 * written regardless of screenshot success.
 *
 * @param highlightTag  The tag that caused the failure, if known. Highlighted in red.
 * @param file          Destination for `screenshot-annotated.png`.
 * @param nodesFile     Destination for `nodes.txt` (text fallback, always written).
 * @return The number of annotated nodes (tagged nodes found in the tree).
 */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.captureAnnotated(
    highlightTag: String?,
    file: java.io.File,
    nodesFile: java.io.File,
): Int {
    // Get the root semantics node (unmerged tree for accurate bounds).
    //
    // `onRoot()` *asserts* there is exactly one root, so it throws as soon as
    // anything composes a second semantics root — which is precisely what
    // `ModalBottomSheet` and other overlays do on desktop. That made this
    // function throw while capturing a failure, and the resulting exception
    // replaced the real one: a test failing on a selector inside a sheet
    // reported "expected exactly 1 node but found 2 nodes that satisfy
    // (isRoot)" instead of the missing tag. Diagnostics must never mask the
    // failure they exist to explain, so take the first root when several exist
    // and record the ambiguity in the text output rather than throwing.
    val roots = onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
    val ambiguous = roots.size > 1
    if (roots.isEmpty()) {
        writeNodesFile(nodesFile, emptyList(), highlightTag)
        return 0
    }
    val rootNode = roots.first()

    val tagged = collectTaggedNodes(rootNode).toMutableList()
    if (ambiguous) {
        // The remaining roots are the overlay that caused the ambiguity; without
        // them the tree dump hides the very nodes a sheet-scoped failure is
        // about. Append them as a flat, tagged-only list.
        for (extra in roots.drop(1)) {
            tagged += collectTaggedNodes(extra)
        }
    }
    val annotated = tagged.size

    // Always write the text fallback.
    writeNodesFile(
        nodesFile,
        tagged,
        if (ambiguous) "$highlightTag [NOTE: ${roots.size} semantics roots; overlay roots appended]".trim()
        else highlightTag,
    )

    // Draw the annotated image.
    runCatching {
        mainClock.autoAdvance = false
        val bitmap = captureToImage()
        val base: BufferedImage = bitmap.toAwtImage()

        // Scale factor: boundsInRoot are in Compose pixels; captureToImage may use a
        // device pixel ratio. Use the base image size as the reference.
        val rootBounds = rootNode.boundsInRoot
        val scaleX = base.width.toFloat() / rootBounds.width
        val scaleY = base.height.toFloat() / rootBounds.height

        val annotatedGraphics = base.graphics.create() as Graphics2D
        annotatedGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        annotatedGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val nearby = highlightTag?.let { wanted ->
            tagged.map { it.second }.filter { it.contains(wanted.take(4), ignoreCase = true) }
        }.orEmpty().toSet()

        for ((node, tag) in tagged) {
            val color = if (tag == highlightTag || tag in nearby) AwtColor(255, 0, 0) else AwtColor(128, 128, 128)
            drawNodeBox(annotatedGraphics, node, tag, color, scaleX, scaleY)
        }

        annotatedGraphics.dispose()
        javax.imageio.ImageIO.write(base, "png", file)
    }

    return annotated
}

/**
 * Collects every node in the semantics tree that carries a testTag, via an
 * unmerged-tree traversal from the root. Shared by [captureAnnotated] (annotated
 * screenshot + nodes.txt) and the baseline regression diff.
 */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.collectTaggedNodes(): List<Pair<SemanticsNode, String>> {
    // Same multi-root tolerance as captureAnnotated: overlays create additional
    // semantics roots, and a single-root assumption here throws instead of
    // reporting what is on screen.
    val roots = onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
    return roots.flatMap { collectTaggedNodes(it) }
}

private fun collectTaggedNodes(root: SemanticsNode): List<Pair<SemanticsNode, String>> {
    val allNodes = mutableListOf<SemanticsNode>()
    visitNodes(root) { allNodes.add(it) }
    return allNodes.mapNotNull { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.let { tag -> node to tag }
    }
}

/**
 * Writes a "last known good" snapshot of the current screen into [dir]:
 * `screenshot-annotated.png`, `nodes.txt` (same formats as the failure bundle)
 * and `tags.txt` — the sorted tag inventory the regression diff compares against.
 *
 * Called from the harness on a passing test when `-Dsingularity.test.baseline=true`.
 * The failure path then reports which tags appeared or disappeared relative to
 * this snapshot — a semantic regression signal that stays stable under pixel
 * noise (animations, antialiasing) which defeats image diffing.
 */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.writeBaseline(dir: java.io.File) {
    dir.mkdirs()
    captureAnnotated(
        highlightTag = null,
        file = java.io.File(dir, "screenshot-annotated.png"),
        nodesFile = java.io.File(dir, "nodes.txt"),
    )
    val tags = collectTaggedNodes().map { it.second }.distinct().sorted()
    java.io.File(dir, "tags.txt").writeText((listOf(tags.size.toString()) + tags).joinToString("\n"))
}

/**
 * Compares the current tag inventory against a baseline written by [writeBaseline].
 *
 * Returns a human-readable diff ("appeared" / "disappeared" tag lists), or null
 * when no baseline exists for this test. A tag that disappeared explains
 * "something the failure removed from the screen"; a new one explains what the
 * failure left behind.
 */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.diffAgainstBaseline(baselineDir: java.io.File): String? {
    val baseline = readBaselineTags(baselineDir) ?: return null

    val current = collectTaggedNodes().map { it.second }.distinct()
    val appeared = current.filterNot { it in baseline }.sorted()
    val disappeared = baseline.filterNot { it in current }.sorted()
    if (appeared.isEmpty() && disappeared.isEmpty()) {
        return "Tag inventory matches baseline (${current.size} tags)."
    }
    return buildString {
        appendLine("Tag inventory differs from baseline (${baseline.size} tags):")
        if (appeared.isNotEmpty()) appendLine("Appeared: ${appeared.joinToString()}")
        if (disappeared.isNotEmpty()) appendLine("Disappeared: ${disappeared.joinToString()}")
    }.trimEnd()
}

/** Reads the tag inventory written by [writeBaseline], or null when unavailable. */
private fun readBaselineTags(baselineDir: java.io.File): Set<String>? {
    val tagsFile = java.io.File(baselineDir, "tags.txt")
    if (!tagsFile.isRegularFileOrNull()) return null
    val lines = runCatching { tagsFile.readLines() }.getOrNull() ?: return null
    if (lines.isEmpty()) return null
    return lines.drop(1).filter { it.isNotBlank() }.toSet()
}

private fun java.io.File.isRegularFileOrNull(): Boolean = runCatching { isFile }.getOrDefault(false)

private fun drawNodeBox(
    g: Graphics2D,
    node: SemanticsNode,
    tag: String,
    color: AwtColor,
    scaleX: Float,
    scaleY: Float,
) {
    val bounds = node.boundsInRoot

    val x = bounds.left * scaleX
    val y = bounds.top * scaleY
    val w = bounds.width * scaleX
    val h = bounds.height * scaleY

    g.stroke = BasicStroke(2f)
    g.color = color
    g.drawRect(x.toInt(), y.toInt(), w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))

    // Label background (semi-transparent version of the outline color)
    val label = tag.take(30)
    g.font = Font(Font.MONOSPACED, Font.PLAIN, 10)
    val fm = g.fontMetrics
    val labelW = fm.stringWidth(label) + 4
    val labelH: Int = fm.height + 2
    g.color = AwtColor(color.red, color.green, color.blue, (color.alpha * 0.7).toInt().coerceIn(0, 255))
    g.fillRect(x.toInt(), y.toInt() - labelH, labelW, labelH)
    g.color = color
    g.drawString(label, x.toInt() + 2, y.toInt() - fm.descent + 1)
}

private fun writeNodesFile(
    file: java.io.File,
    tagged: List<Pair<SemanticsNode, String>>,
    highlightTag: String?,
) {
    val nearby = highlightTag?.let { wanted ->
        tagged.map { it.second }.filter { it.contains(wanted.take(4), ignoreCase = true) }
    }.orEmpty().toSet()

    val lines = buildList<String> {
        add("Semantic nodes with testTag (${tagged.size} total)")
        add("Highlighted: ${highlightTag ?: "(none)"}")
        if (nearby.isNotEmpty()) {
            add("Nearby candidates: ${nearby.joinToString { "'$it'" }}")
        }
        add("")
        for ((node, tag) in tagged) {
            val text = node.config.getOrNull(SemanticsProperties.Text)
                ?.joinToString(" / ") { it.text }
                ?.take(60)
                ?: ""
            val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.joinToString(" / ")
                ?: ""
            val selected = node.config.getOrNull(SemanticsProperties.Selected)
            val b = node.boundsInRoot
            val flag = if (tag == highlightTag || tag in nearby) " ←" else ""
            add("  $tag$flag")
            if (text.isNotEmpty()) add("    text=$text")
            if (desc.isNotEmpty()) add("    contentDescription=$desc")
            if (selected != null) add("    selected=$selected")
            add("    bounds=(${b.left.toInt()},${b.top.toInt()}) w=${b.width.toInt()} h=${b.height.toInt()}")
        }
    }
    runCatching { file.writeText(lines.joinToString("\n")) }
}

/**
 * Visits every [SemanticsNode] in the subtree rooted at [root], calling [visit]
 * for each one (including the root itself). Traversal is depth-first.
 */
private fun visitNodes(root: SemanticsNode, visit: (SemanticsNode) -> Unit) {
    fun recurse(node: SemanticsNode) {
        visit(node)
        node.children.forEach { recurse(it) }
    }
    recurse(root)
}
