package com.singularity.todo.feature.flows.profile

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.typeIntoTag
import org.junit.Test

/**
 * Desktop mirror of `Maestro/flows/profile/01-create-profile.yaml`.
 *
 * Desktop reaches the ProfileSwitcher via the drawer menu (same as Settings, Notes,
 * etc.) — the drawer item carries `contentDescription = "Profiles"` which the test
 * clicks via [clickContentDescription]. The ProfileSwitcher was wired into
 * `menuEntries` in this branch, making it the 7th drawer menu item.
 */
@OptIn(ExperimentalTestApi::class)
class ProfileFlowTest {

    @Test
    fun create_profile_from_drawer() = runDesktopAppTest(checkA11y = true) {
        openDrawer()
        clickContentDescription("Profiles")

        // Wait for drawer to close and ProfileSwitcher to render — use the
        // create button (unique to this screen) rather than "Profiles" text,
        // which also appears in the closed-drawer semantics node.
        assertTagDisplayed(TestTags.PROFILE_CREATE_BUTTON)

        // Create a new profile.
        clickTag(TestTags.PROFILE_CREATE_BUTTON)
        assertTextDisplayed("New Profile")
        typeIntoTag(TestTags.PROFILE_CREATE_NAME_INPUT, "Work")
        clickTag(TestTags.Dialog.CONFIRM)

        // The new profile card appears.
        assertTagDisplayed(TestTags.profileItem("Work"))
    }
}
