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
 * The Android shell's content must clear the navigation suite.
 *
 * Before #1699 the shell dropped the navigation suite's padding and removed the
 * bottom window inset, so the last row of every screen was drawn behind the
 * bottom navigation bar. These tests pin the insets the shell's scaffold pads
 * its content by, so reintroducing that shape fails them — on a phone that is
 * the bottom edge, and on a wide window it is the rail's side.
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
    fun a_system_inset_the_suite_already_took_is_not_counted_twice() {
        val insets =
            shellContentWindowInsets(
                horizontalInsets = WindowInsets(0, 0, 0, 48),
                navigationPadding = PaddingValues(bottom = 48.dp),
            )

        // Union is the maximum per side; a sum would report 48 + 96.
        assertEquals(96, insets.getBottom(density))
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
    fun the_room_a_navigation_rail_took_is_added_on_the_side() {
        val insets =
            shellContentWindowInsets(
                horizontalInsets = WindowInsets(0, 0, 0, 0),
                navigationPadding = PaddingValues(start = 80.dp),
            )

        assertEquals(160, insets.getLeft(density, LayoutDirection.Ltr))
        assertEquals(0, insets.getBottom(density))
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
