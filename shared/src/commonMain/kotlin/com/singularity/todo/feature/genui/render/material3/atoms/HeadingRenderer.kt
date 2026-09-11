package com.singularity.todo.feature.genui.render.material3.atoms

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.genuiTag

/** Maps a heading level to a Material3 typography style. Internal for testing. */
@Composable
@ReadOnlyComposable
internal fun headingStyle(level: Int) = when (level) {
    1 -> MaterialTheme.typography.headlineLarge
    2 -> MaterialTheme.typography.headlineMedium
    3 -> MaterialTheme.typography.headlineSmall
    else -> MaterialTheme.typography.titleLarge
}

internal fun ComponentRegistry.registerHeading(): Unit = register("heading") { node, _, modifier ->
    val h = node as UiNode.Heading
    Text(
        text = h.text,
        style = headingStyle(h.level),
        modifier = modifier.genuiTag(
            name = "heading_${h.text.take(16).replace(" ", "_")}",
            description = "Heading: ${h.text}",
        ),
    )
}
