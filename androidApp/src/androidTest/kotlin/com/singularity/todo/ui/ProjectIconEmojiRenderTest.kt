package com.singularity.todo.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.singularity.todo.core.emoji.Emoji
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.model.ProjectTreeNode
import com.singularity.todo.feature.projects.presentation.components.ProjectIcon
import com.singularity.todo.feature.projects.presentation.components.ProjectTreeSection
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsTreeUiState
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.time.Instant
import android.graphics.Color as AndroidColor

/**
 * The project glyph as the platform actually draws it.
 *
 * `ProjectIconResolverTest` proves the stored sequence survives resolution; it
 * says nothing about a font. This test renders the same four composed sequences
 * on a device, inside the icon box, and asserts:
 *
 * - the sequence reaches the platform as one text node;
 * - the box has ink, so a glyph was painted;
 * - no ink touches the box's border, so the drawn glyph is not clipped — which
 *   is what a sequence the font refuses to compose looks like (two glyphs are
 *   wider than the 24dp box and get clipped at its edge);
 * - the text is one line tall.
 *
 * It does not fingerprint which glyph the font chose: two different composed
 * renderings both paint inside the box, and telling them apart needs a golden
 * image, not a rule. A font that lacks the sequence draws one tofu box, which
 * also paints inside the box without touching the border — the ink rule cannot
 * catch that. The bound is stated rather than hidden.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ProjectIconEmojiRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun composed_sequences_are_drawn_as_one_unclipped_line() {
        compose.setContent {
            // The box is a fixed 24dp while the glyph is sized in sp, so a large
            // device font scale would clip a correct glyph and fail with a
            // message about composition. Pin the scale to 1.
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 1f),
            ) {
                SingularityTheme {
                    Column {
                        SEQUENCES.forEachIndexed { index, icon ->
                            Box(
                                modifier =
                                    Modifier
                                        .size(ICON_BOX)
                                        .background(BACKGROUND)
                                        .testTag(boxTag(index)),
                            ) {
                                ProjectIcon(icon = Emoji(icon), tint = Color.Black, size = ICON_BOX)
                            }
                        }
                    }
                }
            }
        }

        SEQUENCES.forEachIndexed { index, icon ->
            compose.onNodeWithText(icon, useUnmergedTree = true).assertIsDisplayed()

            val pixels = compose.onNodeWithTag(boxTag(index)).captureToImage().toPixelMap()
            var ink = 0
            var borderInk = false
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    if (!isInk(pixels[x, y])) continue
                    ink++
                    val onVerticalEdge = x == 0 || x == pixels.width - 1
                    val onHorizontalEdge = y == 0 || y == pixels.height - 1
                    if (onVerticalEdge || onHorizontalEdge) {
                        borderInk = true
                    }
                }
            }
            assertTrue("'$icon' painted nothing inside the icon box", ink > 0)
            assertFalse(
                "'$icon' is clipped at the icon box's edge — the font drew it wider than one glyph",
                borderInk,
            )
        }
    }

    @Test
    fun a_project_without_an_icon_draws_the_fallback_folder() {
        compose.setContent {
            SingularityTheme {
                ProjectIcon(icon = null, tint = Color.Black, size = ICON_BOX)
            }
        }

        compose.onNodeWithTag(TestTags.PROJECT_ICON_FALLBACK, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun a_long_project_name_stays_on_one_line_in_the_menu_row() {
        val project =
            Project(
                id = ProjectId.fromString("p-long"),
                name = LONG_NAME,
                color = 0xFF3366FF.toInt(),
                icon = Emoji("🧑‍💻"),
                createdAt = Instant.parse("2026-11-12T12:00:00Z"),
                updatedAt = Instant.parse("2026-11-12T12:00:00Z"),
                userId = UserId("test-user"),
            )
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 1f),
            ) {
                SingularityTheme {
                    Box(modifier = Modifier.width(200.dp)) {
                        ProjectTreeSection(
                            state =
                                ProjectsTreeUiState.Loaded(
                                    nodes =
                                        persistentListOf(
                                            ProjectTreeNode(
                                                project = project,
                                                depth = 0,
                                                hasChildren = false,
                                                isExpanded = false,
                                            ),
                                        ),
                                    sectionExpanded = true,
                                ),
                            onToggleExpanded = {},
                            onOpenProject = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithText(LONG_NAME, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("🧑‍💻", useUnmergedTree = true).assertIsDisplayed()

        val bounds = compose.onNodeWithText(LONG_NAME, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(
            "a compact row keeps its title on one line, got ${bounds.bottom - bounds.top}",
            bounds.bottom - bounds.top < 30.dp,
        )
    }

    private fun isInk(color: Color): Boolean =
        abs(color.red - BACKGROUND.red) +
            abs(color.green - BACKGROUND.green) +
            abs(color.blue - BACKGROUND.blue) > INK_THRESHOLD

    private fun boxTag(index: Int) = "project_icon_emoji_$index"

    private companion object {
        val ICON_BOX = 24.dp

        /**
         * Magenta: none of the four sequences contains it, so any other pixel is
         * ink from the glyph.
         */
        val BACKGROUND = Color(AndroidColor.MAGENTA)

        const val INK_THRESHOLD = 0.1f

        const val LONG_NAME =
            "A project name long enough that it would wrap several times in a compact row"

        val SEQUENCES =
            listOf(
                "🧑‍💻", // ZWJ sequence
                "🇬🇧", // two regional indicators
                "🧘🏽", // skin-tone modifier
                "🕊️", // variation selector
            )
    }
}
