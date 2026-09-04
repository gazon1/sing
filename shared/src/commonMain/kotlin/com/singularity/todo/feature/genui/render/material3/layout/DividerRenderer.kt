package com.singularity.todo.feature.genui.render.material3.layout

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Modifier
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.genuiTag

internal fun ComponentRegistry.registerDivider(): Unit = register("divider") { _, _, modifier ->
    HorizontalDivider(modifier = modifier.genuiTag("divider", "Divider").fillMaxWidth())
}
