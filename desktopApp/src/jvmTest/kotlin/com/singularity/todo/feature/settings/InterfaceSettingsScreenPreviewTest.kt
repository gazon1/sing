@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.core.settings.ThemeMode
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.theme.SingularityAccents
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.settings.screens.InterfaceSettingsScreen
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The live preview in Settings → Interface.
 *
 * The preview is a function of the appearance state, and these tests drive the
 * real controls — the accent swatches, the theme selector, the font-size slider —
 * so the intent, the state and the ambient theme are all exercised together, the
 * way the app wires them. A test that hands the theme and the state the same fixed
 * values only proves the components render.
 *
 * The pixel assertions compare the preview against a `primaryContainer` swatch
 * rendered under the same theme. `surface` is accent-tinted too, so "the colour
 * changed" is green even when the accent never reaches the preview; equality with
 * the role is the contract.
 */
@Tag("slow")
@Tag("slow:compose")
class InterfaceSettingsScreenPreviewTest {
    @Test
    fun `selecting an accent repaints the preview`() =
        runIsolatedComposeTest(width = 1024, height = 1400) {
            val accent = mutableStateOf<SingularityAccents>(SingularityAccents.Green)
            setContent {
                PreviewHarness(ThemeMode.Light, accent.value, scale = 1f) { intent ->
                    if (intent is SettingsIntent.Appearance.UpdateAccentColor) {
                        accent.value = SingularityAccents.fromString(intent.value)
                    }
                }
            }
            awaitTag(PREVIEW_NOTE_TAG)
            val green = awaitTag(PREVIEW_NOTE_TAG).leftEdgeArgb()

            awaitTag(TestTags.Settings.accentSwatch("Red")).performClick()
            waitForIdle()

            val red = awaitTag(PREVIEW_NOTE_TAG).leftEdgeArgb()
            assertNotEquals(green, red, "the preview did not follow the accent the user selected")
            assertEquals(
                awaitTag(PRIMARY_CONTAINER_TAG).centerArgb(),
                red,
                "the preview changed, but not to the accent's primaryContainer — it is not " +
                    "reading the palette the rest of the app is painted with",
            )
        }

    @Test
    fun `selecting a theme mode repaints the preview`() =
        runIsolatedComposeTest(width = 1024, height = 1400) {
            val mode = mutableStateOf<ThemeMode>(ThemeMode.Light)
            setContent {
                PreviewHarness(mode.value, SingularityAccents.Green, scale = 1f) { intent ->
                    if (intent is SettingsIntent.Appearance.UpdateThemeMode) mode.value = intent.value
                }
            }
            awaitTag(PREVIEW_NOTE_TAG)
            val light = awaitTag(PREVIEW_NOTE_TAG).leftEdgeArgb()

            awaitTag(TestTags.Settings.themeModeButton("Dark")).performClick()
            waitForIdle()

            val dark = awaitTag(PREVIEW_NOTE_TAG).leftEdgeArgb()
            assertNotEquals(light, dark, "the preview did not follow the theme mode the user selected")
            assertEquals(
                awaitTag(PRIMARY_CONTAINER_TAG).centerArgb(),
                dark,
                "the preview is not painted with the dark palette the theme is using",
            )
        }

    @Test
    fun `the font-size slider grows the preview and clips nothing`() =
        runIsolatedComposeTest(width = 1024, height = 1400) {
            val scale = mutableStateOf(1f)
            setContent {
                PreviewHarness(ThemeMode.Light, SingularityAccents.Green, scale.value) { intent ->
                    if (intent is SettingsIntent.Appearance.UpdateFontSizeScale) scale.value = intent.value
                }
            }
            awaitTag(PREVIEW_NOTE_TAG)
            val small = awaitTag(PREVIEW_NOTE_TAG).fetchSemanticsNode().boundsInRoot.height

            awaitTag(TestTags.Settings.FONT_SIZE_SLIDER).performSemanticsAction(SemanticsActions.SetProgress) {
                it(1.5f)
            }
            waitForIdle()

            val large = awaitTag(PREVIEW_NOTE_TAG).fetchSemanticsNode().boundsInRoot.height
            assertTrue(
                large > small,
                "the note in the preview did not grow with the font scale ($small → $large)",
            )
            // Still on screen after the growth: no clipping, no push off the window.
            assertTextDisplayed("Sample task")
            assertTextDisplayed("Sample note")
        }

    @Test
    fun `the preview renders when the theme follows the system`() =
        runIsolatedComposeTest {
            setContent {
                PreviewHarness(ThemeMode.System, SingularityAccents.Green, scale = 1f) {}
            }

            assertTextDisplayed("Sample task")
            assertTextDisplayed("Sample note")
        }

    /** The preview plus the state and theme wiring a real screen has. */
    @Composable
    private fun PreviewHarness(
        themeMode: ThemeMode,
        accent: SingularityAccents,
        scale: Float,
        onIntent: (SettingsIntent) -> Unit,
    ) {
        SingularityTheme(themeMode = themeMode, accent = accent, fontSizeScale = scale) {
            Column {
                // The role as the theme defines it, the pixel the note card must match.
                Box(
                    Modifier
                        .size(24.dp)
                        .testTag(PRIMARY_CONTAINER_TAG)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                )
                InterfaceSettingsScreen(
                    state =
                        SettingsUiState.Content(
                            appearance =
                                SettingsSection.Appearance(
                                    themeMode = themeMode,
                                    accentColor = accent.name.lowercase(),
                                    fontSizeScale = scale,
                                ),
                        ),
                    onIntent = onIntent,
                )
            }
        }
    }

    /** The fill of a solid node, taken from its middle. */
    private fun SemanticsNodeInteraction.centerArgb(): Int {
        val pixels = captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2].toArgb()
    }

    /**
     * The note card's fill, sampled where no text can be: two pixels in from the left
     * edge at mid height, past the rounded corners and left of the 12dp content padding.
     */
    private fun SemanticsNodeInteraction.leftEdgeArgb(): Int {
        val pixels = captureToImage().toPixelMap()
        return pixels[2, pixels.height / 2].toArgb()
    }

    private companion object {
        const val PRIMARY_CONTAINER_TAG = "theme_preview_primary_container_reference"
        val PREVIEW_NOTE_TAG = TestTags.noteItem("theme-preview-note")
    }
}
