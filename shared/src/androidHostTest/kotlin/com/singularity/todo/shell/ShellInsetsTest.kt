package com.singularity.todo.shell

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

// No `fast`/`slow` tag, and that is not an omission: this is a JUnit4 class under the
// Vintage engine, and Jupiter's `@Tag` does not reach it — the Vintage engine does not map
// `@Tag` onto a platform tag, so `includeTags("fast", "slow")` would exclude it exactly
// like an untagged class. `shared/build.gradle.kts:524-526` is the branch that therefore
// runs `testAndroidHostTest` unfiltered, and
// `TestTagCoverageTest.tag_filtered_source_sets_run_on_an_engine_that_honours_tags` is what
// stops that exemption from quietly spreading to a source set the filter does apply to.

/**
 * The Android shell's content must clear the navigation suite.
 *
 * Before #1699 the shell dropped the navigation suite's padding and removed the bottom
 * window inset, so the last row of every screen was drawn behind the bottom navigation bar.
 * These tests pin the insets the shell's scaffold pads its content by, using the *same
 * input the shell produces* — the full `ScaffoldDefaults.contentWindowInsets`, not a
 * horizontal slice — so the bottom-edge arithmetic is exercised with a non-zero bottom,
 * which is the only shape in which it can be wrong.
 *
 * The one number these tests cannot supply is the library's own: whether the suite's bar
 * height already contains the system navigation bar's inset. That is observed at runtime by
 * the shell and recorded in `docs/device-checks/android-shell-insets-360dp.md`;
 * `a_suite_bar_taller_than_the_system_bar_sets_the_room_the_content_clears` below pins the
 * arithmetic for the reading that observation is expected to confirm — the reading is an
 * expectation, not a measurement, and the test is named after what it asserts.
 */
@RunWith(RobolectricTestRunner::class)
class ShellInsetsTest {
    /** The density the insets are measured at; the values below are in pixels. */
    private val density = Density(density = 2f)

    @Test
    fun the_navigation_bar_height_is_padded_when_the_window_reports_no_bottom_inset() {
        val insets =
            shellContentWindowInsets(
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationPadding = PaddingValues(bottom = 48.dp),
            )

        assertEquals(96, insets.getBottom(density))
    }

    @Test
    fun the_system_navigation_bar_and_the_suite_are_not_summed() {
        // Both report 48 dp of room. The union is the maximum; a sum would say 192.
        val insets =
            shellContentWindowInsets(
                windowInsets = WindowInsets(0, 0, 0, 96),
                navigationPadding = PaddingValues(bottom = 48.dp),
            )

        assertEquals(96, insets.getBottom(density))
    }

    @Test
    fun a_system_bar_taller_than_the_suite_is_not_lost() {
        // 60 dp of system bar under a 48 dp suite: the larger wins, so the row clears both.
        //
        // This is also the shape the shell actually hands in. The call site passes
        // `ScaffoldDefaults.contentWindowInsets` whole, so `getBottom` here is the system
        // navigation bar's inset — the input whose absence made the previous version of this
        // class test arithmetic no caller could produce.
        val insets =
            shellContentWindowInsets(
                windowInsets = WindowInsets(0, 0, 0, 120),
                navigationPadding = PaddingValues(bottom = 48.dp),
            )

        assertEquals(120, insets.getBottom(density))
    }

    @Test
    fun a_suite_bar_taller_than_the_system_bar_sets_the_room_the_content_clears() {
        // The 360 dp phone #2165 / #2166 were filed against: a 24 dp gesture navigation bar
        // under an 80 dp suite bar. This input stands for the reading the device check is
        // expected to confirm — the suite's bar height covers the system bar's inset — so the
        // answer is 80 dp, not 104 dp. A sum here would be a visible 24 dp gap between the
        // last row and the bar.
        //
        // The *reading* is an expectation until a device says otherwise: the numbers below
        // are the case the check is meant to confirm, not an observation. The device run
        // settles them — see `docs/device-checks/android-shell-insets-360dp.md`.
        val insets =
            shellContentWindowInsets(
                windowInsets = WindowInsets(0, 0, 0, 48),
                navigationPadding = PaddingValues(bottom = 80.dp),
            )

        assertEquals(160, insets.getBottom(density))
    }

    @Test
    fun the_status_bar_is_left_to_the_shell_top_bar() {
        // #1566's shape: ShellTopBar consumes the status bar inset itself, so the content
        // must not be padded by it as well. The helper drops the top edge, always.
        val insets =
            shellContentWindowInsets(
                windowInsets = WindowInsets(0, 48, 0, 96),
                navigationPadding = PaddingValues(bottom = 48.dp),
            )

        assertEquals(0, insets.getTop(density))
        assertEquals(96, insets.getBottom(density))
    }

    @Test
    fun the_horizontal_insets_are_kept() {
        val insets =
            shellContentWindowInsets(
                windowInsets = WindowInsets(4, 0, 6, 0),
                navigationPadding = PaddingValues(bottom = 48.dp),
            )

        assertEquals(4, insets.getLeft(density, LayoutDirection.Ltr))
        assertEquals(6, insets.getRight(density, LayoutDirection.Ltr))
    }

    @Test
    fun the_room_a_navigation_rail_took_is_added_on_the_side() {
        val insets =
            shellContentWindowInsets(
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationPadding = PaddingValues(start = 80.dp),
            )

        assertEquals(160, insets.getLeft(density, LayoutDirection.Ltr))
        assertEquals(0, insets.getBottom(density))
    }

    @Test
    fun a_navigation_suite_that_took_no_room_leaves_the_content_where_it_was() {
        val insets =
            shellContentWindowInsets(
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationPadding = PaddingValues(),
            )

        assertEquals(0, insets.getBottom(density))
    }
}
