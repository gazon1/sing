package com.singularity.todo.feature.flows.calendar

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.calendar_sync.domain.model.ImportWindow
import com.singularity.todo.test.fakes.TestUsers
import com.singularity.todo.test.helpers.connectedCredential
import com.singularity.todo.test.helpers.googleCalendarModule
import com.singularity.todo.test.helpers.FakeCalendarEventSource
import com.singularity.todo.test.helpers.FakeGoogleCalendarSettingsRepository
import com.singularity.todo.test.helpers.FakeGoogleCredentialStore
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagExists
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.clickTagScrolled
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.scrollToTag
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Duration

/**
 * A grant with no refresh token is called out before sync silently stops.
 *
 * Covers `CAL-SYNC-RENEW-01` — see
 * `infra/kiwi/scenarios/calendar/sync/renew/CAL-SYNC-RENEW-01.yaml`.
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
class CalendarSyncRenewScenarioTest {

    @Test
    @DisplayName("CAL-SYNC-RENEW-01 a grant that cannot renew is called out before it silently stops")
    fun a_grant_that_cannot_renew_is_called_out() =
        runDesktopAppTest(
            overrides = googleCalendarModule(
                credentials = FakeGoogleCredentialStore(
                    initial = mapOf(
                        // Seeded directly rather than clicked: the renewal warning is
                        // reachable only from a credential that already has no refresh
                        // token, and no amount of clicking in a JVM test produces one.
                        TestUsers.DEFAULT.value to connectedCredential(canRenew = false),
                    ),
                ),
                settings = FakeGoogleCalendarSettingsRepository(),
                eventSource = FakeCalendarEventSource(),
            ),
        ) {
            openDrawer()
            clickContentDescription("Settings")
            clickTag(TestTags.settingsTab("Calendar"))
            clickTag(TestTags.CalendarSync.providerSegment("Google Calendar"))

            // Connected — the screen cannot tell the difference from a renewable
            // grant on the account section alone — and the warning is the only thing
            // that distinguishes the two. Asserting it separately is the point: a
            // test that only checked for the Disconnect button would pass with this
            // line deleted.
            assertTagDisplayed(TestTags.CalendarSync.GOOGLE_RENEW_WARNING)
        }
}
