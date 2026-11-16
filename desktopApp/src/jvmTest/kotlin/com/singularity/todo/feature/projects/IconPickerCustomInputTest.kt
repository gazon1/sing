package com.singularity.todo.feature.projects

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.emoji.Emoji
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.projects.presentation.components.IconPickerSheet
import com.singularity.todo.test.helpers.ScreenWithOwners
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.typeIntoTag
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The picker's free-text emoji field.
 *
 * The field is the one way into the icon that the catalog does not offer, so
 * its contract is the app's own and it is stated here rather than left to the
 * code:
 *
 * - the field takes **any** text. It is not restricted to one emoji and not
 *   checked against the catalog, because a catalog miss must not reject a
 *   value that is a perfectly good composed emoji — the provider stores some
 *   of the app's own forms (`🕊️`, `1️⃣`) as *variants*, not as entries of
 *   their own, so "is it in the catalog?" is the wrong question;
 * - the value is taken **verbatim**: no normalization, no stripped variation
 *   selector, no split ZWJ sequence. What the user typed is what is stored;
 * - an **empty or whitespace-only** field adds nothing. The sheet stays open
 *   and nothing is picked, so a stray space cannot become a project icon.
 *
 * #2932 owns the product decision on whether stricter validation belongs
 * here. This test pins today's behavior so that decision has a baseline.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("fast")
class IconPickerCustomInputTest {
    @Test
    fun `an empty field adds nothing`() =
        runIsolatedComposeTest {
            val picks = pickerPicks()

            clickTag(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertTrue(picks.isEmpty(), "an empty field picked ${picks.size} value(s)")
        }

    @Test
    fun `a whitespace-only field adds nothing`() =
        runIsolatedComposeTest {
            val picks = pickerPicks()

            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_INPUT, "   ")
            clickTag(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertTrue(picks.isEmpty(), "a whitespace-only field picked ${picks.size} value(s)")
        }

    @Test
    fun `ordinary text is added as it was typed`() =
        runIsolatedComposeTest {
            val picks = pickerPicks()

            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_INPUT, "marketing")
            clickTag(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertEquals(listOf(Emoji("marketing")), picks)
        }

    @Test
    fun `a single emoji is added unchanged`() =
        runIsolatedComposeTest {
            val picks = pickerPicks()

            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_INPUT, "😀")
            clickTag(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertEquals(listOf(Emoji("😀")), picks)
        }

    @Test
    fun `a composed sequence is added unchanged`() =
        runIsolatedComposeTest {
            val picks = pickerPicks()

            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_INPUT, "🧑‍💻")
            clickTag(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertEquals(listOf(Emoji("🧑‍💻")), picks)
            assertTrue(
                picks.single().value.contains("‍"),
                "the zero-width joiner is still there — the sequence was not split",
            )
        }

    /**
     * A form the catalog holds only as a variant is still added.
     *
     * The dove is stored by the app as `🕊️` — the emoji-presentation form.
     * The provider holds the text-presentation base and the form beside it, so
     * a lookup in the catalog's entries alone would not find the app's own
     * value. The field does not look it up.
     */
    @Test
    fun `a form the catalog holds as a variant is added unchanged`() =
        runIsolatedComposeTest {
            val picks = pickerPicks()

            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_INPUT, "🕊️")
            clickTag(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertEquals(listOf(Emoji("🕊️")), picks)
        }

    /**
     * A sequence the catalog does not know is added unchanged.
     *
     * The field makes no claim that what it is given is an emoji. A value that
     * is not in the catalog is stored as it is — it does not crash the picker
     * and it does not rewrite the text.
     */
    @Test
    fun `an unknown sequence is added unchanged`() =
        runIsolatedComposeTest {
            val picks = pickerPicks()

            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_INPUT, "a‍b")
            clickTag(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertEquals(listOf(Emoji("a‍b")), picks)
        }

    /**
     * The value the picker hands back is the value that gets stored.
     *
     * The domain keeps the icon as its exact Unicode string, so the picker
     * handing back a changed string would change what a project stores.
     */
    @Test
    fun `the picked value keeps every code point`() =
        runIsolatedComposeTest {
            val picks = pickerPicks()

            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_INPUT, "👨‍👩‍👧‍👦")
            clickTag(TestTags.PROJECT_ICON_CUSTOM_ADD)

            val picked = picks.single()
            assertEquals("👨‍👩‍👧‍👦", picked.value)
            assertEquals(
                7,
                picked.value.codePointCount(0, picked.value.length),
                "the four people and their three joiners are still there",
            )
        }

    /**
     * The sheet, and the picks it hands back.
     *
     * The sheet is drawn and driven here, so the assertions are about what a
     * user does in it: type into the field, press "Add".
     */
    private fun DesktopComposeUiTest.pickerPicks(): MutableList<Emoji> {
        val picked = mutableListOf<Emoji>()
        setContent {
            ScreenWithOwners {
                IconPickerSheet(
                    currentIcon = null,
                    recentEmojis = persistentListOf(),
                    onPick = { value -> value?.let(picked::add) },
                    onDismiss = {},
                )
            }
        }
        return picked
    }
}
