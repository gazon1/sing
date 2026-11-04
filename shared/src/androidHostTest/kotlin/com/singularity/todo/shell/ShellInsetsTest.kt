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

// No `fast`/`slow` tag: this is a JUnit4 class under the Vintage engine, and
// Jupiter's `@Tag` does not reach it. `testAndroidHostTest` is exempt from the
// tag filter for exactly that reason (shared/build.gradle.kts:519-543).

/**
 * The Android shell's content must clear the navigation bar.
 *
 * The shell drops the navigation suite's padding and removes the bottom window
 * inset, and the last row of every screen is then drawn behind the bottom
 * navigation bar. These tests pin the insets the shell's scaffold pads its
 * content by: a drop of the navigation bar's height fails them.
 */
@RunWith(RobolectricTestRunner::class)
class ShellInsetsTest {
    /** The density the insets are measured at; the values below are in pixels. */
    private val density = Density(density = 2f)

    @Test
    fun the_navigation_bar_height_is_added_to_the_content_insets() {
        val insets =
            shellContentWindowInsets(
                horizontalInsets = WindowInsets(0, 0, 0, 0),
                navigationPadding = PaddingValues(bottom = 48.dp),
            )

        assertEquals(96, insets.getBottom(density))
    }

    @Test
    fun the_content_is_not_padded_at_the_top() {
        val insets =
            shellContentWindowInsets(
                horizontalInsets = WindowInsets(0, 0, 0, 0),
                navigationPadding = PaddingValues(bottom = 48.dp),
            )

        assertEquals(0, insets.getTop(density))
    }

    @Test
    fun the_horizontal_insets_are_kept() {
        val insets =
            shellContentWindowInsets(
                horizontalInsets = WindowInsets(4, 0, 6, 0),
                navigationPadding = PaddingValues(bottom = 48.dp),
            )

        assertEquals(4, insets.getLeft(density, LayoutDirection.Ltr))
        assertEquals(6, insets.getRight(density, LayoutDirection.Ltr))
    }

    @Test
    fun a_navigation_suite_that_took_no_room_leaves_the_content_where_it_was() {
        val insets =
            shellContentWindowInsets(
                horizontalInsets = WindowInsets(0, 0, 0, 0),
                navigationPadding = PaddingValues(),
            )

        assertEquals(0, insets.getBottom(density))
    }
}
