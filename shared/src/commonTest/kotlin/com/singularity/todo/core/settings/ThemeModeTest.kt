package com.singularity.todo.core.settings

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ThemeMode.isDark] — the single definition of how a mode becomes a dark flag.
 *
 * The theme builds the palette from this answer and the Android system bars take
 * their polarity from it, so a mode that resolved differently in the two places
 * would paint the app light while the bars claimed it was dark. These cases pin
 * the resolution itself: the two modes that do not follow the system ignore it
 * entirely, and the mode that follows it passes it through unchanged.
 */
@Tag("fast")
class ThemeModeTest {

    @Test
    fun `the system mode follows a dark system`() {
        assertTrue(ThemeMode.System.isDark(systemDark = true))
    }

    @Test
    fun `the system mode follows a light system`() {
        assertFalse(ThemeMode.System.isDark(systemDark = false))
    }

    @Test
    fun `the light mode is light on a dark system`() {
        assertFalse(ThemeMode.Light.isDark(systemDark = true))
    }

    @Test
    fun `the light mode is light on a light system`() {
        assertFalse(ThemeMode.Light.isDark(systemDark = false))
    }

    @Test
    fun `the dark mode is dark on a light system`() {
        assertTrue(ThemeMode.Dark.isDark(systemDark = false))
    }

    @Test
    fun `the dark mode is dark on a dark system`() {
        assertTrue(ThemeMode.Dark.isDark(systemDark = true))
    }
}
