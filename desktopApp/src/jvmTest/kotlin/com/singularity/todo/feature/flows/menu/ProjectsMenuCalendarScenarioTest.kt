package com.singularity.todo.feature.flows.menu

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.feature.flows.menu.ProjectsMenuScenarioSupport.CLOCK
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop automation for the menu scenario `MENU-04` — the Calendar is an entry of the menu.
 *
 * The drawer is the desktop's menu, and it draws the same tree the Android
 * sheet draws: one composable, two drawings.
 *
 * The projects are seeded through the repository rather than through the UI,
 * because a tree is about the arrangement of projects the user already has,
 * and building that arrangement through the editor would be a slower way of
 * saying the same thing.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class ProjectsMenuCalendarScenarioTest {

    @Test
    @DisplayName("MENU-04 the Calendar is an entry of the menu")
    fun the_calendar_is_an_entry_of_the_menu() = runDesktopAppTest(clock = CLOCK, checkA11y = true) {
        openDrawer()

        assertTextDisplayed("Calendar")
    }
}
