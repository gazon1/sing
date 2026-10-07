package com.singularity.todo.feature.flows.calendar

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.TestUsers
import com.singularity.todo.test.helpers.connectedCredential
import com.singularity.todo.test.helpers.googleCalendarModule
import com.singularity.todo.test.helpers.FakeCalendarEventSource
import com.singularity.todo.test.helpers.FakeGoogleSettings
import com.singularity.todo.test.helpers.FakeGoogleCredentialStore
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
 * A failed Google pass reports its failure instead of leaving the panel unchanged.
 *
 * Covers `CAL-SYNC-SYNCNOW-01` — see
 * `infra/kiwi/scenarios/calendar/sync/syncnow/CAL-SYNC-SYNCNOW-01.yaml`.
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
class CalendarSyncNowScenarioTest {

    @Test
    @DisplayName("CAL-SYNC-SYNCNOW-01 a failed pass says so instead of leaving the panel unchanged")
    fun a_failed_pass_is_reported() =
        runDesktopAppTest(
            overrides = googleCalendarModule(
                credentials = FakeGoogleCredentialStore(
                    initial = mapOf(TestUsers.DEFAULT.value to connectedCredential()),
                ),
                settings = FakeGoogleSettings(calId = "primary-cal"),
                eventSource = FakeCalendarEventSource(),
                // The pass throws. The coordinator turns that into Outcome.Failed,
                // which is a different answer from declining — and the reason this
                // carrier exists: before the three-way Outcome, a failure was
                // reported through `skipped`, the worker read it as "did not run",
                // and returned success. The user saw nothing at all.
                passFailure = "Google Calendar is unreachable (test)",
            ),
        ) {
            openDrawer()
            clickContentDescription("Settings")
            clickTag(TestTags.settingsTab("Calendar"))
            clickTag(TestTags.CalendarSync.providerSegment("Google Calendar"))

            // Below the fold on a 1024x768 window: the Google panel stacks account,
            // calendar picker, import and sync sections. `assertIsDisplayed` fails on a
            // node that is present and clickable but off-screen, so this scrolls first.
            scrollToTag(TestTags.CalendarSync.GOOGLE_SYNC_NOW_BUTTON)

            // No "last synced" line before the first pass has run — the outcome node
            // is absent, not empty, so this cannot pass on a zero-length render.
            assertTagNotExists(TestTags.CalendarSync.GOOGLE_SYNC_OUTCOME)

            clickTagScrolled(TestTags.CalendarSync.GOOGLE_SYNC_NOW_BUTTON)

            // The failure reaches the user, with its reason. Still below the fold — the
            // outcome line renders directly under the button just pressed.
            scrollToTag(TestTags.CalendarSync.GOOGLE_SYNC_OUTCOME)
            assertTextDisplayed("Sync failed: Google Calendar is unreachable (test)")
        }
}
