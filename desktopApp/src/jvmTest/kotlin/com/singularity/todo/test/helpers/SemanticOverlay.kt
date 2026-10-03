package com.singularity.todo.test.helpers

import androidx.compose.ui.graphics.toAwtImage
import java.awt.Color as AwtColor
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onRoot
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
    val rootNode = onRoot(useUnmergedTree = true).fetchSemanticsNode()

    // Collect ALL nodes in the tree by traversing from root using visit().
    val allNodes = mutableListOf<SemanticsNode>()
    visitNodes(rootNode) { allNodes.add(it) }

    // Filter to only nodes that carry a testTag.
    val tagged: List<Pair<SemanticsNode, String>> = allNodes.mapNotNull { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.let { tag -> node to tag }
    }
    val annotated = tagged.size

    // Always write the text fallback.
    writeNodesFile(nodesFile, tagged, highlightTag)

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
