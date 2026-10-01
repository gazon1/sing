package com.singularity.todo.feature.flows.profile

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.Test

/**
 * Desktop mirror of `Maestro/flows/profile/01-create-profile.yaml`.
 *
 * Desktop reaches the ProfileSwitcher via the drawer menu (same as Settings, Notes,
 * etc.) — the drawer item carries `contentDescription = "Profiles"` which the test
 * matches via `onNodeWithContentDescription`. The ProfileSwitcher was wired into
 * `menuEntries` in this branch, making it the 7th drawer menu item.
 */
@OptIn(ExperimentalTestApi::class)
class ProfileFlowTest {

    @Test
    fun create_profile_from_drawer() = runDesktopAppTest(checkA11y = true) {
        openDrawer()
        onNodeWithContentDescription("Profiles").performClick()

        // Wait for drawer to close and ProfileSwitcher to render — use the
        // create button (unique to this screen) rather than "Profiles" text,
        // which also appears in the closed-drawer semantics node.
        onNodeWithTag(TestTags.PROFILE_CREATE_BUTTON).assertIsDisplayed()

        // Create a new profile.
        onNodeWithTag(TestTags.PROFILE_CREATE_BUTTON).performClick()
        onNodeWithText("New Profile").assertIsDisplayed()
        onNodeWithTag(TestTags.PROFILE_CREATE_NAME_INPUT).performTextReplacement("Work")
        onNodeWithTag(TestTags.Dialog.CONFIRM).performClick()

        // The new profile card appears.
        onNodeWithTag(TestTags.profileItem("Work")).assertIsDisplayed()
    }
}
