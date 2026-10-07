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
 * The system panel says why it cannot work and leaves the working provider reachable.
 *
 * Covers `CAL-SYNC-SYSTEM-01` — see
 * `infra/kiwi/scenarios/calendar/sync/system/CAL-SYNC-SYSTEM-01.yaml`.
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
class CalendarSyncSystemScenarioTest {

    @Test
    @DisplayName("CAL-SYNC-SYSTEM-01 the system panel says why it cannot work and leaves Google selectable")
    fun the_system_panel_says_why_it_cannot_work() = runDesktopAppTest {
        openDrawer()
        clickContentDescription("Settings")
        clickTag(TestTags.settingsTab("Calendar"))

        // The message names the constraint *and* reassures the user their tasks are
        // unaffected — a bare "unsupported" reads as data loss.
        assertTagDisplayed(TestTags.CalendarSync.SYSTEM_UNAVAILABLE)
        // The full sentence, not a prefix: `assertTextDisplayed` matches the node's whole
        // text exactly (`onNodeWithText(..., useUnmergedTree = true)`), so a substring
        // here fails on a node that is present and correct.
        assertTextDisplayed(
            "System calendar sync needs Android. Your tasks are unaffected — they stay in the app.",
        )

        // And the working alternative is still offered. This is the assertion that
        // would have failed before the gates were moved below the selector.
        assertTagExists(TestTags.CalendarSync.providerSegment("Google Calendar"))

        // Crucially: no enable switch and no empty picker over providers that were
        // never queried. Rendering controls for a calendar that cannot exist is how
        // a user ends up toggling something that only looks like it works.
        assertTagNotExists(TestTags.CalendarSync.SYSTEM_ENABLE_SWITCH)
        assertTagNotExists(TestTags.CalendarSync.SYSTEM_SYNC_NOW_BUTTON)
}
