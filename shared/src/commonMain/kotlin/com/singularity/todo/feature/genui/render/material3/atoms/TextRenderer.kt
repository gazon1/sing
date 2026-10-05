package com.singularity.todo.feature.genui.render.material3.atoms

import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import com.singularity.todo.core.ui.theme.GenUiToneColors
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.genuiTag

/** Maps [UiNode.Tone] to a display color. Internal so it can be unit-tested. */
internal fun toneColor(tone: UiNode.Tone): Color = when (tone) {
    UiNode.Tone.Error -> GenUiToneColors.Error
    UiNode.Tone.Warning -> GenUiToneColors.Warning
    UiNode.Tone.Positive -> GenUiToneColors.Positive
    else -> Color.Unspecified
}

internal fun ComponentRegistry.registerText(): Unit = register("text") { node, _, modifier ->
    val t = node as UiNode.Text
    Text(
        text = t.value,
        color = toneColor(t.tone),
        modifier = modifier.genuiTag(
            name = "text_${t.value.take(16).replace(" ", "_")}",
            description = "Text: ${t.value.take(32)}",
        ),
    )
}
