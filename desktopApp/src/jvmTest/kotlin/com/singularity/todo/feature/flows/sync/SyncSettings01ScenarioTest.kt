package com.singularity.todo.feature.flows.sync

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.clickContentDescriptionScrolled
import com.singularity.todo.test.helpers.clickSettingsTab
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.textsOfTag
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Covers `SYNC-SETTINGS-01` — see
 * `scripts/src/traceability/scenarios/sync/settings/SYNC-SETTINGS-01.yaml`.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class SyncSettings01ScenarioTest {
    @Test
    @DisplayName("SYNC-SETTINGS-01 change how often sync runs")
    fun change_interval_cycles_value() =
        runDesktopAppTest(checkA11y = true) {
            openDrawer()
            clickContentDescriptionScrolled("Settings")
            clickSettingsTab("Sync")

            // Interval row is reachable, and tapping cycles its value.
            assertTagDisplayed(TestTags.Sync.INTERVAL_ROW)
            // The *value* is the row's trailing text — the first one is the title,
            // which is "Interval" before the tap and after it.
            val before = textsOfTag(TestTags.Sync.INTERVAL_ROW).lastOrNull()
            clickTag(TestTags.Sync.INTERVAL_ROW)
            val after = textsOfTag(TestTags.Sync.INTERVAL_ROW).lastOrNull()
            check(before != null && before != after) { "interval value did not change after tap: $before" }

            // Auto-sync switch reachable.
            assertTagDisplayed(TestTags.Sync.AUTO_SYNC_SWITCH)
        }
}
