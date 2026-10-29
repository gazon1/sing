package com.singularity.todo.feature.flows.sync

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.clickContentDescriptionScrolled
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Covers `SYNC-STATUS-01` — see
 * `scripts/src/traceability/scenarios/sync/status/SYNC-STATUS-01.yaml`.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class SyncStatus01ScenarioTest {

    @Test
    @DisplayName("SYNC-STATUS-01 See what sync is doing, and start it by hand")
    fun sync_status_and_manual_sync_reachable() =
        runDesktopAppTest(checkA11y = true) {
            openDrawer()
            clickContentDescriptionScrolled("Settings")
            clickTag(TestTags.settingsTab("Sync"))

            // Manual sync action is reachable.
            assertTagDisplayed(TestTags.Sync.SYNC_NOW_ROW)
        }
}