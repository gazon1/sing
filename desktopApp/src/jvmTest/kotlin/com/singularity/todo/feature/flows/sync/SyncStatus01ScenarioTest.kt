package com.singularity.todo.feature.flows.sync

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.sync.SyncEngineStatus
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeSyncRepository
import com.singularity.todo.test.helpers.TIMEOUT_MS
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTagTextEquals
import com.singularity.todo.test.helpers.clickContentDescriptionScrolled
import com.singularity.todo.test.helpers.clickSettingsTab
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers `SYNC-STATUS-01` — see
 * `scripts/src/traceability/scenarios/sync/status/SYNC-STATUS-01.yaml`.
 *
 * The indicator is the value on the sync-now row: `SyncSettingsScreen` reads it
 * from `SyncEngineStatus.label()`, which is the four states this flow asserts.
 * The engine is the harness's fake, injected through `overrides`, so the flow
 * can put it in each state without a server.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class SyncStatus01ScenarioTest {
    @Test
    @DisplayName("SYNC-STATUS-01 See what sync is doing, and start it by hand")
    fun each_status_reads_as_itself_and_one_press_runs_one_cycle() {
        val sync = FakeSyncRepository()

        runDesktopAppTest(
            overrides = module { single<SyncRepository> { sync } },
            checkA11y = true,
        ) {
            openDrawer()
            clickContentDescriptionScrolled("Settings")
            clickSettingsTab("Sync")

            // Manual sync action is reachable.
            assertTagDisplayed(TestTags.Sync.SYNC_NOW_ROW)

            // Each state reads as what it is. `awaitTagTextEquals` rather than a
            // one-shot read: the view model's status collector updates the row a
            // frame or two after the fake changes.
            awaitTagTextEquals(TestTags.Sync.SYNC_NOW_ROW, "Idle")

            sync.setStatus(SyncEngineStatus.Pushing)
            awaitTagTextEquals(TestTags.Sync.SYNC_NOW_ROW, "Syncing…")

            sync.setStatus(SyncEngineStatus.NoConnection)
            awaitTagTextEquals(TestTags.Sync.SYNC_NOW_ROW, "Offline")

            sync.setStatus(SyncEngineStatus.Failure(AppError.Network("the sync server is unreachable")))
            awaitTagTextEquals(TestTags.Sync.SYNC_NOW_ROW, "Error")

            // Pressing the row runs one cycle. The press is debounced while a
            // cycle is in flight (`SyncViewModel.syncNow` returns when the status
            // is running), so the row is put back to Idle first.
            sync.setStatus(SyncEngineStatus.Idle)
            awaitTagTextEquals(TestTags.Sync.SYNC_NOW_ROW, "Idle")

            // Counted as a delta: a background cycle would otherwise make "one
            // cycle" ambiguous.
            val cyclesBeforePress = sync.syncOnceCalls
            clickTag(TestTags.Sync.SYNC_NOW_ROW)
            waitUntil(
                conditionDescription = "the sync-now press ran a cycle",
                timeoutMillis = TIMEOUT_MS,
            ) {
                sync.syncOnceCalls > cyclesBeforePress
            }
            assertEquals(
                cyclesBeforePress + 1,
                sync.syncOnceCalls,
                "one press of the sync-now row must run exactly one sync cycle",
            )
        }
    }
}
