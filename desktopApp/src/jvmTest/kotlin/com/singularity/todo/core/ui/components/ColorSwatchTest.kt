@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.core.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The swatch draws its content on a colour the user chose, so the content has to
 * stay readable on it: a white tick on a pale swatch is invisible.
 *
 * The swatch therefore hands its content the foreground that contrasts with its
 * own background, and the caller does not have to name a tint.
 */
@Tag("fast")
class ColorSwatchTest {
    @Test
    fun `a light swatch draws its content in black`() =
        runIsolatedComposeTest {
            var contentColor: Color? = null
            setContent {
                ColorSwatch(
                    color = Color.White,
                    name = "White",
                    selected = false,
                    onClick = {},
                ) {
                    contentColor = LocalContentColor.current
                }
            }
            waitForIdle()

            assertEquals(Color.Black, contentColor)
        }

    @Test
    fun `a dark swatch draws its content in white`() =
        runIsolatedComposeTest {
            var contentColor: Color? = null
            setContent {
                ColorSwatch(
                    color = Color(0xFF1A237E),
                    name = "Indigo",
                    selected = false,
                    onClick = {},
                ) {
                    contentColor = LocalContentColor.current
                }
            }
            waitForIdle()

            assertEquals(Color.White, contentColor)
        }
}
