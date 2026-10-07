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
 * The import window the screen describes is the one the pass is configured with.
 *
 * Covers `CAL-SYNC-IMPORT-01` — see
 * `infra/kiwi/scenarios/calendar/sync/import/CAL-SYNC-IMPORT-01.yaml`.
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
class CalendarSyncImportScenarioTest {

    @Test
    @DisplayName("CAL-SYNC-IMPORT-01 the import window shown is the one the pass is configured with")
    fun the_import_window_shown_matches_the_configured_one() =
        runDesktopAppTest(
            overrides = googleCalendarModule(
                credentials = FakeGoogleCredentialStore(
                    initial = mapOf(TestUsers.DEFAULT.value to connectedCredential()),
                ),
                settings = FakeGoogleCalendarSettingsRepository(selectedCalendarId = "primary-cal"),
                eventSource = FakeCalendarEventSource(),
                // Deliberately NOT the default. The sentence is derived from the
                // value DI handed the engine and the event source, so a screen that
                // still read ImportWindow.DEFAULT here would print different numbers
                // than the pass it describes. Three sites used to name that constant
                // independently and nothing would have complained.
                importWindow = ImportWindow(
                    past = Duration.parse("7d"),
                    future = Duration.parse("14d"),
                ),
            ),
        ) {
            openDrawer()
            clickContentDescription("Settings")
            clickTag(TestTags.settingsTab("Calendar"))
            clickTag(TestTags.CalendarSync.providerSegment("Google Calendar"))

            assertTagDisplayed(TestTags.CalendarSync.GOOGLE_IMPORT_SWITCH)
            assertTagDisplayed(TestTags.CalendarSync.GOOGLE_IMPORT_WINDOW)
            assertTextDisplayed("Imports events from the past 7 days to 14 days.")
        }
}
