package com.singularity.todo.feature.genui.render.material3.atoms

import androidx.compose.ui.graphics.toArgb
import com.singularity.todo.feature.genui.catalog.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals

class ToneColorTest {

    @Test
    fun positiveToneMapsToGreen() {
        assertEquals(0xFF2E7D32.toInt(), toneColor(UiNode.Tone.Positive).toArgb())
    }

    @Test
    fun errorToneMapsToRed() {
        assertEquals(0xFFFF0000.toInt(), toneColor(UiNode.Tone.Error).toArgb())
    }

    @Test
    fun warningToneMapsToAmber() {
        assertEquals(0xFFCC7700.toInt(), toneColor(UiNode.Tone.Warning).toArgb())
    }

    @Test
    fun defaultToneIsUnspecified() {
        assertEquals(androidx.compose.ui.graphics.Color.Unspecified, toneColor(UiNode.Tone.Default))
    }
}
