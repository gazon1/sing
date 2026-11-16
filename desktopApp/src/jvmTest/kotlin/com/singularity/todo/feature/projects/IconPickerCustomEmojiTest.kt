package com.singularity.todo.feature.projects

import androidx.compose.ui.test.DesktopComposeUiTest
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
import kotlin.test.assertTrue

/**
 * The project icon picker's custom entry.
 *
 * ## What is under test
 *
 * The sheet offers a free-text field next to the catalog: whatever the user
 * types there becomes the icon. The field is the one way into the icon that the
 * catalog does not offer, so its contract is the app's own and it is stated
 * here rather than left to the code:
 *
 * - the field takes **any** text. It is not restricted to one emoji and not
 *   checked against the catalog, because a catalog miss must not reject a
 *   value that is a perfectly good composed emoji — the provider stores some
 *   of the app's own forms (`🕊️`, `1️⃣`) as *variants*, not as entries of
 *   their own, so "is it in the catalog?" is the wrong question;
 * - the text is **trimmed**: the outer whitespace is dropped and the inner
 *   whitespace is kept. What survives the trim is then taken as it is — no
 *   normalization, no stripped variation selector, no split ZWJ sequence;
 * - an **empty or whitespace-only** field adds nothing: no icon is picked and
 *   the sheet does not close, so a stray space cannot become a project icon.
 *
 * "Trimmed, then taken as it is" is the whole contract. A field that kept
 * the outer spaces would store an icon that no longer looks like what the user
 * typed; a field that trimmed the inner ones would cut a phrase in half.
 *
 * The rule itself is asserted in common code
 * (`CustomEmojiInputTest`); this test is the binding that puts it behind the
 * field and the button: the text the field holds is what the button hands
 * back.
 *
 * ## What a blank input must not do
 *
 * The field is a free-text field, so an empty one is a normal state and not an
 * error. Nothing is picked, the field still holds what the user typed, and the
 * next attempt starts from there. A button that cleared the field, or that
 * closed the sheet, would make the second attempt a different thing than the
 * first.
 *
 * #2932 owns the product decision on whether stricter validation belongs
 * here. This test pins today's behavior so that decision has a baseline.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("fast")
class IconPickerCustomEmojiTest {
    @Test
    fun `an empty field adds nothing`() =
        runIsolatedComposeTest {
            val session = pickerSession()

            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertTrue(session.picks.isEmpty(), "an empty field picked ${session.picks.size} value(s)")
            assertEquals(0, session.emptyPicks, "an empty field picked the empty icon")
            assertEquals(0, session.dismissals, "an empty field closed the sheet")
        }

    @Test
    fun `a whitespace-only field adds nothing and keeps what was typed`() =
        runIsolatedComposeTest {
            val session = pickerSession()

            scrollToTag(TestTags.PROJECT_ICON_CUSTOM_FIELD)
            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_FIELD, "   ")
            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertTrue(session.picks.isEmpty(), "a whitespace-only field picked ${session.picks.size} value(s)")
            assertEquals(0, session.emptyPicks, "a whitespace-only field picked the empty icon")
            assertEquals(0, session.dismissals, "a whitespace-only field closed the sheet")
            // The sheet is still open — the field is there — and it still holds
            // what was typed: a blank field is a state, not a reset.
            assertTagTextEquals(TestTags.PROJECT_ICON_CUSTOM_FIELD, "   ")

            // The field is still usable: the second attempt is the first one.
            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_FIELD, "🧑‍💻")
            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertEquals(listOf(Emoji("🧑‍💻")), session.picks, "the second attempt picked what was typed")
        }

    @Test
    fun `ordinary text is added as it was typed`() =
        runIsolatedComposeTest {
            val picks = pickerSession().picks

            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_FIELD, "marketing")
            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertEquals(listOf(Emoji("marketing")), picks)
        }

    @Test
    fun `a single emoji is added unchanged`() =
        runIsolatedComposeTest {
            val picks = pickerSession().picks

            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_FIELD, "😀")
            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertEquals(listOf(Emoji("😀")), picks)
        }

    /**
     * The composed sequence is the case the whole contract is about.
     *
     * A ZWJ sequence is several code points joined into one glyph. Nothing
     * between the field and the icon parses it, so it has to arrive at the
     * picker callback as the exact string that was typed.
     */
    @Test
    fun `a composed sequence is added unchanged`() =
        runIsolatedComposeTest {
            val picks = pickerSession().picks

            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_FIELD, "🧑‍💻")
            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertEquals(listOf(Emoji("🧑‍💻")), picks)
            assertEquals(1, picks.size, "the pick was handed back once")
            assertEquals("🧑‍💻", picks.single().value, "and it survived byte for byte")
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
            val picks = pickerSession().picks

            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_FIELD, "🕊️")
            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

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
            val picks = pickerSession().picks

            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_FIELD, "a‍b")
            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

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
            val picks = pickerSession().picks

            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_FIELD, "👨‍👩‍👧‍👦")
            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

            val picked = picks.single()
            assertEquals("👨‍👩‍👧‍👦", picked.value)
            assertEquals(
                7,
                picked.value.codePointCount(0, picked.value.length),
                "the four people and their three joiners are still there",
            )
        }

    /**
     * The outer whitespace is dropped.
     *
     * A field that kept the spaces around the text would store an icon that
     * no longer looks like what the user typed, and the spaces would be
     * invisible in the picker.
     */
    @Test
    fun `outer whitespace is trimmed from the value`() =
        runIsolatedComposeTest {
            val picks = pickerSession().picks

            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_FIELD, "  🎯  ")
            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertEquals(listOf(Emoji("🎯")), picks, "the outer spaces are gone")
        }

    /**
     * The inner whitespace is kept.
     *
     * Trimming is about the edges of the value, not about the spaces inside
     * it: a phrase is one value, and cutting it in half would store something
     * the user never typed.
     */
    @Test
    fun `inner whitespace is kept in the value`() =
        runIsolatedComposeTest {
            val picks = pickerSession().picks

            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_FIELD, "rocket ship")
            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertEquals(listOf(Emoji("rocket ship")), picks, "the inner space is still there")
        }

    /**
     * The sequences the catalog holds as entries of their own.
     *
     * A flag and a keycap are several code points each, and the field has to
     * hand them back as they were typed — the catalog's idea of what one emoji
     * is does not get a say here.
     */
    @Test
    fun `a flag is added unchanged`() =
        runIsolatedComposeTest {
            val picks = pickerSession().picks

            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_FIELD, "🇬🇧")
            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertEquals(listOf(Emoji("🇬🇧")), picks)
        }

    @Test
    fun `a skin-tone form is added unchanged`() =
        runIsolatedComposeTest {
            val picks = pickerSession().picks

            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_FIELD, "🧘🏽")
            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertEquals(listOf(Emoji("🧘🏽")), picks)
        }

    @Test
    fun `a keycap is added unchanged`() =
        runIsolatedComposeTest {
            val picks = pickerSession().picks

            typeIntoTag(TestTags.PROJECT_ICON_CUSTOM_FIELD, "1️⃣")
            clickTagScrolled(TestTags.PROJECT_ICON_CUSTOM_ADD)

            assertEquals(listOf(Emoji("1️⃣")), picks)
        }

    /**
     * The sheet, and what it hands back.
     *
     * The sheet is drawn and driven here, so the assertions are about what a
     * user does in it: type into the field, press "Add".
     *
     * Three things are recorded, because "nothing happened" is three
     * different claims: an icon was picked, the empty icon was picked, or the
     * sheet was asked to close.
     */
    private fun DesktopComposeUiTest.pickerSession(): PickerSession {
        val session = PickerSession()
        setContent {
            ScreenWithOwners {
                IconPickerSheet(
                    currentIcon = null,
                    recentEmojis = persistentListOf(),
                    onPick = { value -> session.record(value) },
                    onDismiss = { session.dismissals++ },
                )
            }
        }
        return session
    }

    private class PickerSession {
        /** The icons the sheet handed back. */
        val picks = mutableListOf<Emoji>()

        /** How often the sheet handed back the empty icon. */
        var emptyPicks = 0

        /** How often the sheet asked to be closed. */
        var dismissals = 0

        fun record(value: Emoji?) {
            if (value == null) emptyPicks++ else picks += value
        }
    }
}
