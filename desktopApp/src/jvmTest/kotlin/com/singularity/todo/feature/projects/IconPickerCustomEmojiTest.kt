package com.singularity.todo.feature.projects

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.emoji.Emoji
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.projects.presentation.components.IconPickerSheet
import com.singularity.todo.test.helpers.ScreenWithOwners
import com.singularity.todo.test.helpers.assertTagTextEquals
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
 *
 * ## What a blank input must not do
 *
 * The field is a free-text field, so an empty one is a normal state and not an
 * error. Nothing is picked, the sheet stays open with the text the user typed
 * still in it, and the next attempt starts from there. A button that cleared
 * the field, or that closed the sheet, would make the second attempt a
 * different thing than the first.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("fast")
class IconPickerCustomEmojiTest {
    @Test
    fun `a typed emoji is picked exactly as it was typed`() =
        runIsolatedComposeTest {
            val picks = mutableListOf<Emoji?>()

            setContent {
                ScreenWithOwners {
                    IconPickerSheet(
                        currentIcon = null,
                        recentEmojis = persistentListOf(),
                        onPick = { picks.add(it) },
                        onDismiss = {},
                    )
                }
            }

            scrollToTag(TestTags.PROJECT_ICON_CUSTOM_FIELD)
            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_FIELD, "🧑‍💻")
            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertEquals(1, picks.size, "the pick was handed back once")
            assertEquals(Emoji("🧑‍💻"), picks.single(), "the composed sequence was picked")
            assertEquals("🧑‍💻", picks.single()?.value, "and it survived byte for byte")
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
    fun `a blank field picks nothing and keeps what was typed`() =
        runIsolatedComposeTest {
            val picks = mutableListOf<Emoji?>()

            setContent {
                ScreenWithOwners {
                    IconPickerSheet(
                        currentIcon = null,
                        recentEmojis = persistentListOf(),
                        onPick = { picks.add(it) },
                        onDismiss = {},
                    )
                }
            }

            scrollToTag(TestTags.PROJECT_ICON_CUSTOM_FIELD)
            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_FIELD, "   ")
            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertEquals(0, picks.size, "whitespace is not an icon")
            // The sheet is still open — the field is there — and it still holds
            // what was typed: a blank field is a state, not a reset.
            assertTagTextEquals(TestTags.PROJECT_ICON_CUSTOM_FIELD, "   ")

            // The field is still usable: the second attempt is the first one.
            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_FIELD, "🧑‍💻")
            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertEquals(1, picks.size, "the second attempt picked once")
            assertEquals("🧑‍💻", picks.single()?.value, "and it picked what was typed")
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
            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertNull(picked, "there is nothing to pick")
        }
}
