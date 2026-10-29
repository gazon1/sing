package com.singularity.todo.feature.flows.sync

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.clickContentDescriptionScrolled
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.textOfTag
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
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
            clickTag(TestTags.settingsTab("Sync"))

            // Interval row is reachable, and tapping cycles its value.
            assertTagDisplayed(TestTags.Sync.INTERVAL_ROW)
            val before = textOfTag(TestTags.Sync.INTERVAL_ROW)
            clickTag(TestTags.Sync.INTERVAL_ROW)
            val after = textOfTag(TestTags.Sync.INTERVAL_ROW)
            check(before != null && before != after) { "interval value did not change after tap: $before" }

            // Auto-sync switch reachable.
            assertTagDisplayed(TestTags.Sync.AUTO_SYNC_SWITCH)
        }
}