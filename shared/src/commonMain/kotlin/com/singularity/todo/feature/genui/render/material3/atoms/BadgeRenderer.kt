package com.singularity.todo.feature.genui.render.material3.atoms

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.genuiTag

internal fun ComponentRegistry.registerBadge(): Unit = register("badge") { node, _, modifier ->
    val b = node as UiNode.Badge
    val color = toneColor(b.tone).takeIf { it != Color.Unspecified }
        ?: MaterialTheme.colorScheme.primary
    SuggestionChip(
        onClick = { },
        label = { Text(b.text) },
        modifier = modifier.genuiTag("badge_${b.text}", "Badge: ${b.text}"),
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = color.copy(alpha = 0.15f),
            labelColor = color,
        ),
    )
}
