package com.singularity.todo.feature.projects

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.emoji.Emoji
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.projects.presentation.components.IconPickerSheet
import com.singularity.todo.test.helpers.ScreenWithOwners
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.typeIntoTag
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The project icon picker's recently used emoji.
 *
 * ## What is under test
 *
 * The sheet offers the emoji the user picked before at the top of the next
 * opening — with no history there is no "Recent" section at all, because an
 * empty heading is a blank the user has to read past every time.
 *
 * The sheet draws the history it is given and picks what the user taps. It
 * neither reads nor writes the history: that is the ViewModel's job, and it is
 * why this test can hand the sheet a list and watch what it draws.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("fast")
class IconPickerRecentEmojiTest {
    @Test
    fun `the sheet has no recent section while nothing was picked`() =
        runIsolatedComposeTest {
            setContent {
                ScreenWithOwners {
                    IconPickerSheet(
                        currentIcon = null,
                        recentEmojis = persistentListOf(),
                        onPick = {},
                        onDismiss = {},
                    )
                }
            }

            assertTagNotExists(TestTags.PROJECT_ICON_RECENT_SECTION)
        }

    @Test
    fun `a recently picked emoji is offered back`() =
        runIsolatedComposeTest {
            setContent {
                ScreenWithOwners {
                    IconPickerSheet(
                        currentIcon = null,
                        // Not one of the emoji the sheet offers itself, so the
                        // text below can only come from the recent row.
                        recentEmojis = persistentListOf("🦄"),
                        onPick = {},
                        onDismiss = {},
                    )
                }
            }

            assertTagDisplayed(TestTags.PROJECT_ICON_RECENT_SECTION)
            assertTextDisplayed("🦄")
        }

    @Test
    fun `picking an emoji hands the emoji back`() =
        runIsolatedComposeTest {
            var picked: Emoji? = null

            setContent {
                ScreenWithOwners {
                    IconPickerSheet(
                        currentIcon = null,
                        recentEmojis = persistentListOf(),
                        onPick = { picked = it },
                        onDismiss = {},
                    )
                }
            }

            clickText("😀")

            assertEquals(Emoji("😀"), picked, "the emoji was picked")
        }

    @Test
    fun `the search field narrows the catalog to a shortcode match`() =
        runIsolatedComposeTest {
            setContent {
                ScreenWithOwners {
                    IconPickerSheet(
                        currentIcon = null,
                        recentEmojis = persistentListOf(),
                        onPick = {},
                        onDismiss = {},
                    )
                }
            }

            typeIntoTag(TestTags.PROJECT_ICON_SEARCH, "rocket")

            assertTextDisplayed("🚀")
            assertTagNotExists(TestTags.PROJECT_ICON_NO_MATCHES)
        }

    @Test
    fun `a query without a shortcode match says so`() =
        runIsolatedComposeTest {
            setContent {
                ScreenWithOwners {
                    IconPickerSheet(
                        currentIcon = null,
                        recentEmojis = persistentListOf(),
                        onPick = {},
                        onDismiss = {},
                    )
                }
            }

            typeIntoTag(TestTags.PROJECT_ICON_SEARCH, "zzznomatch")

            assertTagDisplayed(TestTags.PROJECT_ICON_NO_MATCHES)
        }
}
