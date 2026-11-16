package com.singularity.todo.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.singularity.todo.core.emoji.Emoji
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.projects.presentation.components.IconPickerSheet
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The picker's free-text field on a device.
 *
 * The desktop pins the field's contract in `IconPickerCustomInputTest`, and the
 * two platforms run the same composable — but a shared composable is not a
 * shared test: the platform owns the text input, the semantics tree and the
 * keyboard, and each of them can break the contract on its own. So the device
 * runs the sheet too.
 *
 * What is pinned here is the contract, not the pixels:
 *
 * - the field takes any text, and it is not checked against the catalog;
 * - the text is trimmed — the outer whitespace goes, the inner whitespace
 *   stays — and what survives is taken as it is;
 * - an empty or whitespace-only field adds nothing;
 * - the picker draws a composed sequence as one sequence.
 *
 * The last one is the Android half of what `ProjectIconEmojiRenderTest` proves
 * for the icon box: the sheet's own chip reaches the screen with the whole
 * sequence in it, not as the pieces it is made of.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class IconPickerCustomInputTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun an_ordinary_text_is_added_as_it_was_typed() {
        val picks = showPicker()

        typeIntoCustomField("marketing")
        pressAdd()

        assertEquals(listOf(Emoji("marketing")), picks)
    }

    @Test
    fun a_composed_emoji_is_added_unchanged() {
        val picks = showPicker()

        typeIntoCustomField("🧑‍💻")
        pressAdd()

        assertEquals(listOf(Emoji("🧑‍💻")), picks)
    }

    @Test
    fun a_flag_is_added_unchanged() {
        val picks = showPicker()

        typeIntoCustomField("🇬🇧")
        pressAdd()

        assertEquals(listOf(Emoji("🇬🇧")), picks)
    }

    @Test
    fun outer_whitespace_is_trimmed_from_the_value() {
        val picks = showPicker()

        typeIntoCustomField("  marketing  ")
        pressAdd()

        assertEquals("the outer spaces are gone", listOf(Emoji("marketing")), picks)
    }

    @Test
    fun inner_whitespace_is_kept_in_the_value() {
        val picks = showPicker()

        typeIntoCustomField("rocket ship")
        pressAdd()

        assertEquals("the inner space is still there", listOf(Emoji("rocket ship")), picks)
    }

    @Test
    fun an_empty_field_adds_nothing() {
        val picks = showPicker()

        pressAdd()

        assertTrue("an empty field picked ${picks.size} value(s)", picks.isEmpty())
    }

    @Test
    fun a_whitespace_only_field_adds_nothing() {
        val picks = showPicker()

        typeIntoCustomField("   ")
        pressAdd()

        assertTrue("a whitespace-only field picked ${picks.size} value(s)", picks.isEmpty())
    }

    @Test
    fun the_picker_draws_a_composed_sequence() {
        showPicker(recent = listOf("🧑‍💻"))

        // The recent row's chip: one node carrying the whole sequence. A sheet
        // that split the sequence would put the pieces in separate nodes, and
        // this would find nothing to assert on.
        compose
            .onAllNodesWithText("🧑‍💻", useUnmergedTree = true)
            .onFirst()
            .assertIsDisplayed()
    }

    /**
     * The catalog's own composed entries reach the screen on a device too.
     *
     * The recent row proves the sheet draws a sequence it was handed. This
     * proves the other half: the provider is installed on Android, its
     * shortcode search answers, and the entry it returns — a ZWJ sequence —
     * is drawn as one node rather than as the pieces it is made of.
     */
    @Test
    fun the_picker_draws_a_composed_sequence_from_the_catalog() {
        showPicker()

        compose.onNodeWithTag(TestTags.PROJECT_ICON_SEARCH).performTextReplacement("technologist")

        compose
            .onAllNodesWithText("🧑‍💻", useUnmergedTree = true)
            .onFirst()
            .assertIsDisplayed()
    }

    /**
     * The sheet, and the picks it hands back.
     *
     * The sheet is drawn and driven here, so the assertions are about what a
     * user does in it: type into the field, press "Add".
     */
    private fun showPicker(recent: List<String> = emptyList()): MutableList<Emoji> {
        val picked = mutableListOf<Emoji>()
        compose.setContent {
            SingularityTheme {
                IconPickerSheet(
                    currentIcon = null,
                    recentEmojis = persistentListOf<String>().addingAll(recent),
                    onPick = { value -> value?.let(picked::add) },
                    onDismiss = {},
                )
            }
        }
        return picked
    }

    private fun typeIntoCustomField(text: String) {
        compose.onNodeWithTag(TestTags.PROJECT_ICON_CUSTOM_INPUT).performTextReplacement(text)
    }

    private fun pressAdd() {
        compose.onNodeWithTag(TestTags.PROJECT_ICON_CUSTOM_ADD).performClick()
    }
}
