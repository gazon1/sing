package com.singularity.todo.feature.genui.render.material3.atoms

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.genuiTag

/** Resolves a string icon name to a Material icon. Public for testing. */
internal fun resolveIcon(name: String): ImageVector? = when (name) {
    "star" -> Icons.Filled.Star
    "check" -> Icons.Filled.Check
    "close" -> Icons.Filled.Close
    "add" -> Icons.Filled.Add
    "delete" -> Icons.Filled.Delete
    "edit" -> Icons.Filled.Edit
    "settings" -> Icons.Filled.Settings
    "search" -> Icons.Filled.Search
    "info" -> Icons.Filled.Info
    "warning" -> Icons.Filled.Warning
    else -> null
}

internal fun ComponentRegistry.registerIcon(): Unit = register("icon") { node, _, modifier ->
    val i = node as UiNode.Icon
    val icon = resolveIcon(i.name)
    if (icon != null) {
        Icon(
            imageVector = icon,
            contentDescription = i.name,
            modifier = modifier.genuiTag("icon_${i.name}", "Icon: ${i.name}"),
        )
    }
    // Silent skip — unknown icon name is skipped, no placeholder text.
}
