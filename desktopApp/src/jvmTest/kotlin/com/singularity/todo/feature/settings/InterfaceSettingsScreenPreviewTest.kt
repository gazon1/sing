@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.unit.dp
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

/**
 * The live preview in Settings → Interface.
 *
 * It is drawn with the production task and note components on the ambient
 * [SingularityTheme], so two things are worth pinning: both samples survive every
 * theme mode, and the accent actually reaches the preview.
 *
 * The second one is the whole reason the note is rendered selected. The task row
 * paints priority colours and never the accent, so the note's `primaryContainer`
 * card is the only element that carries it — and `surface` is tinted by the accent
 * too, which means "the colour changed" passes even when the accent is gone. The
 * test therefore compares the card against a `primaryContainer` swatch rendered
 * under the same theme.
 *
 * Three separate mode tests rather than one loop: a Compose test mounts its content
 * once, and re-mounting inside a loop is not what the harness supports.
 */
@Tag("slow")
@Tag("slow:compose")
class InterfaceSettingsScreenPreviewTest {
    @Test
    fun `the preview renders a task and a note in light mode`() = assertPreview(ThemeMode.Light)

    @Test
    fun `the preview renders a task and a note in dark mode`() = assertPreview(ThemeMode.Dark)

    @Test
    fun `the preview renders a task and a note when the theme follows the system`() = assertPreview(ThemeMode.System)

    @Test
    fun `the note in the preview is painted with the accent's primary container`() =
        runIsolatedComposeTest {
            setContent {
                SingularityTheme(themeMode = ThemeMode.Light, accent = SingularityAccents.Green) {
                    Column {
                        // The role as the theme defines it, the pixel the card must match.
                        Box(
                            Modifier
                                .size(24.dp)
                                .testTag(PRIMARY_CONTAINER_TAG)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                        )
                        InterfaceSettingsScreen(state = content(ThemeMode.Light), onIntent = {})
                    }
                }
            }
            awaitTag(PREVIEW_NOTE_TAG)

            assertEquals(
                awaitTag(PRIMARY_CONTAINER_TAG).centerArgb(),
                awaitTag(PREVIEW_NOTE_TAG).leftEdgeArgb(),
                "the note card in the preview is not painted with primaryContainer — the " +
                    "accent no longer reaches the preview",
            )
        }

    private fun assertPreview(mode: ThemeMode) =
        runIsolatedComposeTest {
            setContent {
                SingularityTheme(themeMode = mode, accent = SingularityAccents.Green) {
                    InterfaceSettingsScreen(state = content(mode), onIntent = {})
                }
            }

            assertTextDisplayed("Sample task")
            assertTextDisplayed("Sample note")
        }

    private fun content(mode: ThemeMode) =
        SettingsUiState.Content(
            appearance =
                SettingsSection.Appearance(
                    themeMode = mode,
                    accentColor = SingularityAccents.Green.name.lowercase(),
                    fontSizeScale = 1.25f,
                ),
        )

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
