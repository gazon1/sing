package com.singularity.todo.feature.flows.calendar

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.TestUsers
import com.singularity.todo.test.helpers.connectedCredential
import com.singularity.todo.test.helpers.googleCalendarModule
import com.singularity.todo.test.helpers.FakeCalendarEventSource
import com.singularity.todo.test.helpers.FakeGoogleSettings
import com.singularity.todo.test.helpers.FakeGoogleCredentialStore
import com.singularity.todo.test.helpers.assertTagDisplayed
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

/**
 * The manual sync control appears only once a calendar has been chosen.
 *
 * Covers `CAL-SYNC-CONNECT-01` — see
 * `infra/kiwi/scenarios/calendar/sync/connect/CAL-SYNC-CONNECT-01.yaml`.
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
class CalendarSyncConnectScenarioTest {

    @Test
    @DisplayName("CAL-SYNC-CONNECT-01 the sync control appears only once a calendar is chosen")
    fun connect_offers_a_control_only_after_a_calendar_is_chosen() =
        runDesktopAppTest(
            overrides = googleCalendarModule(
                // Seeded *connected* rather than connected by clicking, and the reason is
                // the honest one: `SetGoogleConnected(true)` optimistically flips the flag
                // and then re-reads the credential store, so a store with nothing in it
                // puts the screen straight back to "not connected". That is correct — the
                // credential comes from a real OAuth grant, which a JVM test cannot
                // produce. A test that clicked Connect and expected the panel to change
                // would be asserting that a button fabricates a token.
                credentials = FakeGoogleCredentialStore(
                    initial = mapOf(TestUsers.DEFAULT.value to connectedCredential()),
                ),
                settings = FakeGoogleSettings(),
                eventSource = FakeCalendarEventSource(),
            ),
        ) {
            openDrawer()
            clickContentDescription("Settings")
            clickTag(TestTags.settingsTab("Calendar"))
            clickTag(TestTags.CalendarSync.providerSegment("Google Calendar"))

            // Connected: the invitation is gone and the account can be disconnected.
            assertTagDisplayed(TestTags.CalendarSync.GOOGLE_DISCONNECT_BUTTON)
            assertTagNotExists(TestTags.CalendarSync.GOOGLE_CONNECT_BUTTON)

            // Still no sync control — connected is not the same as configured, and the
            // panel says which half is missing rather than offering a dead button.
            assertTagNotExists(TestTags.CalendarSync.GOOGLE_SYNC_NOW_BUTTON)
            // The prompt lives in the Google Sync section at the bottom of a scrolling
            // panel, so it is in the tree but off-screen: scroll before asserting it is
            // *displayed*, or the assertion fails on a node that is present and correct.
            scrollToTag(TestTags.CalendarSync.GOOGLE_SYNC_NEEDS_CALENDAR)
            assertTextDisplayed("Choose a calendar above to start syncing.")

            // The account's calendars are listed. Two rows, so "the row I clicked is
            // selected" is distinguishable from "the only row is selected".
            clickTagScrolled(TestTags.CalendarSync.googleCalendarRow("work-cal"))
            assertTagDisplayed(TestTags.CalendarSync.GOOGLE_SYNC_NOW_BUTTON)
            assertTagNotExists(TestTags.CalendarSync.GOOGLE_SYNC_NEEDS_CALENDAR)
        }
}
