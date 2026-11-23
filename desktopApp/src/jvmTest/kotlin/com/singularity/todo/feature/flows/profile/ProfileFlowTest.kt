package com.singularity.todo.feature.flows.profile

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickContentDescriptionScrolled
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.typeIntoTag
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop carrier of `PROFILE-01` — see
 * `scripts/src/traceability/scenarios/profile/PROFILE-01.yaml`.
 *
 * Desktop reaches the ProfileSwitcher via the drawer menu (same as Settings, Notes,
 * etc.) — the drawer item carries `contentDescription = "Profiles"` which the test
 * clicks via [clickContentDescriptionScrolled]. The ProfileSwitcher was wired into
 * `menuEntries`, making it the 7th drawer menu item.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class ProfileFlowTest {
    @Test
    @DisplayName("PROFILE-01 A second profile is created and listed")
    fun create_profile_from_drawer() =
        runDesktopAppTest(checkA11y = true) {
            openDrawer()
            clickContentDescriptionScrolled("Profiles")

            // Wait for drawer to close and ProfileSwitcher to render — use the
            // create button (unique to this screen) rather than "Profiles" text,
            // which also appears in the closed-drawer semantics node.
            assertTagDisplayed(TestTags.PROFILE_CREATE_BUTTON)

            // Create a new profile.
            clickTag(TestTags.PROFILE_CREATE_BUTTON)
            assertTextDisplayed("New Profile")
            typeIntoTag(TestTags.PROFILE_CREATE_NAME_INPUT, "Work")
            clickTag(TestTags.Dialog.CONFIRM)

            // Dialog closes and the new profile card appears.
            awaitTagGone(TestTags.PROFILE_CREATE_NAME_INPUT)
            assertTagDisplayed(TestTags.profileItem("Work"))
        }
}
