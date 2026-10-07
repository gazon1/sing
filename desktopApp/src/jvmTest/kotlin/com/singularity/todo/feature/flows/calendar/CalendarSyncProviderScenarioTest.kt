package com.singularity.todo.feature.flows.calendar

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagExists
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * The provider selector swaps the panel without leaking the other half's state.
 *
 * Covers `CAL-SYNC-PROVIDER-01` — see
 * `infra/kiwi/scenarios/calendar/sync/provider/CAL-SYNC-PROVIDER-01.yaml`.
 *
 * ## Why one scenario per file
 *
 * `link_index` (infra/kiwi/traceability/coverage.py) rejects two scenarios claimed by one
 * source file, and the reason is mechanical rather than stylistic: a JUnit XML carries one
 * `<testcase>` per method under one `<testsuite classname=…>`, so a result cannot be
 * attributed to a scenario when the file claims several. An earlier version of these carriers
 * lived in one class and failed on exactly that — the message named two `CAL-SYNC-*` ids and
 * the same file.
 *
 * ## What these do not verify
 *
 * They drive the **real** `CalendarSyncViewModel` against faked Google ports
 * (`FakeGooglePorts.kt`). Nothing here mocks the ViewModel: a carrier for a scenario must be
 * evidence about the product, and a mocked ViewModel is evidence about the mock.
 *
 * The gap each leaves is written down rather than papered over. No carrier asserts
 * persistence across a process restart (a DataStore property), and none asserts what Google
 * *holds* after an edit — which needs a second account, and is why `CAL-SYNC-RECUR-01` is
 * recorded unreachable on both tiers rather than claimed.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class CalendarSyncProviderScenarioTest {
    @Test
    @DisplayName("CAL-SYNC-PROVIDER-01 switching provider swaps the panel without leaking the other's state")
    fun switching_provider_swaps_the_panel() = runDesktopAppTest {
        openDrawer()
        clickContentDescription("Settings")
        clickTag(TestTags.settingsTab("Calendar"))

        // The system half is what the screen opens on, and on desktop that is the
        // branch that must *say* it cannot work rather than render controls over
        // repositories nobody asked.
        assertTagDisplayed(TestTags.CalendarSync.SYSTEM_UNAVAILABLE)

        // Both segments are offered before either is chosen. This is the part that
        // regressed once: the gates used to sit above the selector, so a desktop
        // user was shown a permission prompt for a feature that cannot work here
        // and never saw the one that can.
        val google = TestTags.CalendarSync.providerSegment("Google Calendar")
        val system = TestTags.CalendarSync.providerSegment("System calendar")
        assertTagExists(google)
        assertTagExists(system)

        clickTag(google)
        // The Google half is up, and the system half's gate is gone with it.
        assertTagExists(TestTags.CalendarSync.GOOGLE_CONNECT_BUTTON)
        assertTagNotExists(TestTags.CalendarSync.SYSTEM_UNAVAILABLE)

        clickTag(system)
        // Switching back restores the system branch rather than leaving the Google
        // rows behind: the two lists come from different sources, and showing one
        // provider's rows under the other's heading is the defect the reset prevents.
        assertTagDisplayed(TestTags.CalendarSync.SYSTEM_UNAVAILABLE)
        assertTagNotExists(TestTags.CalendarSync.GOOGLE_CONNECT_BUTTON)
    }
}
