@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.settings

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.performSemanticsAction
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.settings.screens.WorkScheduleSettingsScreen
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.scrollToTag
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Greeting Hours rows are measured in hours, the Work Day and Lunch Break
 * rows in minutes, and they render through one component that has to tell them
 * apart.
 *
 * It did not, and nothing could see it from the state: a stored `12` displayed
 * as "12:00" while the slider beside it ranged over a whole day of minutes, so
 * the thumb sat at the far left and the first notch wrote `15`. The agenda then
 * read `15` as an hour and said "Good morning" until the afternoon — and after
 * a picker round trip, never.
 *
 * The window is tall on purpose: the greeting section is the last one in the
 * scrolling column, and a slider that is not on screen cannot be driven.
 */
@Tag("fast")
class WorkScheduleSettingsScreenTest {
    @Test
    fun `greeting sliders are measured in hours`() =
        runIsolatedComposeTest(width = 600, height = 2000) {
            setContent {
                WorkScheduleSettingsScreen(state = SettingsUiState.Content(), onIntent = {})
            }

            scrollToTag(TestTags.Settings.greetingSlider("morning"))
            awaitTag(TestTags.Settings.greetingSlider("morning"))
                .assertRangeInfoEquals(ProgressBarRangeInfo(12f, 0f..23f, steps = 22))

            awaitTag(TestTags.Settings.greetingSlider("afternoon"))
                .assertRangeInfoEquals(ProgressBarRangeInfo(18f, 0f..23f, steps = 22))
        }

    @Test
    fun `one notch on the morning slider writes an hour`() =
        runIsolatedComposeTest(width = 600, height = 2000) {
            val intents = mutableListOf<SettingsIntent>()
            setContent {
                WorkScheduleSettingsScreen(
                    state = SettingsUiState.Content(),
                    onIntent = { intents += it },
                )
            }

            scrollToTag(TestTags.Settings.greetingSlider("morning"))
            awaitTag(TestTags.Settings.greetingSlider("morning"))
                .performSemanticsAction(SemanticsActions.SetProgress) { it(13f) }

            assertEquals(
                listOf(SettingsIntent.Greeting.UpdateMorningEnd(13)),
                intents,
                "a greeting slider's step is an hour, which is what the agenda compares",
            )
        }
}
