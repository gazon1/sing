package com.singularity.todo.feature.genui.render.material3.atoms

import androidx.compose.ui.graphics.toArgb
import com.singularity.todo.feature.genui.catalog.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals

class ToneColorTest {

    @Test
    fun `positive tone maps to green`() {
        assertEquals(0xFF2E7D32.toInt(), toneColor(UiNode.Tone.Positive).toArgb())
    }

    @Test
    fun `error tone maps to red`() {
        assertEquals(0xFFFF0000.toInt(), toneColor(UiNode.Tone.Error).toArgb())
    }

    @Test
    fun `warning tone maps to amber`() {
        assertEquals(0xFFCC7700.toInt(), toneColor(UiNode.Tone.Warning).toArgb())
    }

    @Test
    fun `default tone is unspecified`() {
        assertEquals(androidx.compose.ui.graphics.Color.Unspecified, toneColor(UiNode.Tone.Default))
    }
}
