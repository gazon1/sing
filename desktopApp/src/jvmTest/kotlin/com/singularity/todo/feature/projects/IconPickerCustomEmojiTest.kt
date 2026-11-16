package com.singularity.todo.feature.projects

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.emoji.Emoji
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.projects.presentation.components.IconPickerSheet
import com.singularity.todo.test.helpers.ScreenWithOwners
import com.singularity.todo.test.helpers.clickTagScrolled
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.scrollToTag
import com.singularity.todo.test.helpers.typeIntoTag
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The project icon picker's custom entry.
 *
 * ## What is under test
 *
 * The sheet offers a free-text field next to the catalog: whatever the user
 * types there becomes the icon. The value is the typed text, trimmed — a
 * composed sequence survives as the exact string it was typed as, because
 * nothing between the field and the icon normalizes, parses or looks the value
 * up. Blank input picks nothing at all.
 *
 * The rule itself is asserted in common code
 * (`CustomEmojiInputTest`); this test is the binding that puts it behind the
 * field and the button: the text the field holds is what the button hands
 * back.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("fast")
class IconPickerCustomEmojiTest {
    @Test
    fun `a typed emoji is picked exactly as it was typed`() =
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

            scrollToTag(TestTags.PROJECT_ICON_CUSTOM_FIELD)
            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_FIELD, "🧑‍💻")
            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertEquals(Emoji("🧑‍💻"), picked, "the composed sequence was picked")
            assertEquals("🧑‍💻", picked?.value, "and it survived byte for byte")
        }

    @Test
    fun `a typed value is trimmed`() =
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

            scrollToTag(TestTags.PROJECT_ICON_CUSTOM_FIELD)
            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_FIELD, "  🎯  ")
            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertEquals(Emoji("🎯"), picked, "the padding is not part of the icon")
        }

    @Test
    fun `an empty custom field picks nothing`() =
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

            scrollToTag(TestTags.PROJECT_ICON_CUSTOM_FIELD)
            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_FIELD, "   ")
            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertNull(picked, "whitespace is not an icon")
        }
}
