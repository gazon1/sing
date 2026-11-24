package com.singularity.todo.feature.flows.sync

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.core.sync.SyncScope
import com.singularity.todo.core.sync.SyncScopeProvider
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.FakeSyncRepository
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.clickContentDescriptionScrolled
import com.singularity.todo.test.helpers.clickSettingsTab
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.textsOfTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Covers `SYNC-SETTINGS-01` — see
 * `scripts/src/traceability/scenarios/sync/settings/SYNC-SETTINGS-01.yaml`.
 *
 * The gap this closes is the one the Maestro flow cannot prove headlessly: the
 * interval must take effect without a restart and survive one. The flow keeps the
 * Android settings-sheet journey; this desktop carrier owns the value change, the
 * apply point, and the restart/reload boundary.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class SyncSettings01ScenarioTest {
    @Test
    @DisplayName("SYNC-SETTINGS-01 change how often sync runs")
    fun change_interval_cycles_value() =
        runDesktopAppTest(checkA11y = true) {
            openSyncSettings()

            val before = intervalValue()
            clickTag(TestTags.Sync.INTERVAL_ROW)
            val after = intervalValue()
            check(before != null && before != after) { "interval value did not change after tap: $before" }

            assertTagDisplayed(TestTags.Sync.AUTO_SYNC_SWITCH)
        }

    @Test
    @DisplayName("SYNC-SETTINGS-01 the changed interval is applied and survives restart")
    fun changed_interval_is_applied_and_survives_restart() {
        val db = FakeAppDatabase()
        val sync = FakeSyncRepository()
        val scopeProvider = FixedSyncScopeProvider(SyncScope("user-1", "profile-1"))
        val overrides =
            module {
                single<com.singularity.todo.core.database.AppDatabase> { db }
                single<SyncRepository> { sync }
                single<SyncScopeProvider> { scopeProvider }
            }

        lateinit var selectedInterval: String

        runDesktopAppTest(overrides = overrides) {
            openSyncSettings()
            // Auto-sync is on by default for a fresh scope; changing the interval while
            // it is enabled must re-arm scheduled sync immediately (the "applied" proof).
            val before = intervalValue()
            val scheduledBeforeChange = sync.scheduledIntervals.size
            clickTag(TestTags.Sync.INTERVAL_ROW)
            val after = intervalValue()
            check(before != null && before != after) { "interval value did not change after tap: $before" }
            selectedInterval = checkNotNull(after)

            // Applied at the intended point, not merely stored: changing the interval
            // while auto-sync is enabled re-arms the scheduled sync with the new value.
            val expectedMinutes = minutesOf(selectedInterval)
            waitUntil(
                conditionDescription = "interval change re-arms scheduled sync with $expectedMinutes min",
                timeoutMillis = 10_000,
            ) {
                sync.scheduledIntervals.drop(scheduledBeforeChange).any {
                    it.inWholeMinutes.toInt() == expectedMinutes
                }
            }
        }

        // Reload boundary: a fresh app mount over the same database must show the
        // persisted interval. This is the headless restart-survival proof the Maestro
        // flow cannot make.
        runDesktopAppTest(overrides = overrides) {
            openSyncSettings()
            val reloaded = intervalValue()
            check(reloaded == selectedInterval) {
                "interval did not survive restart/reload: expected $selectedInterval, got $reloaded"
            }
        }
    }

    private fun DesktopComposeUiTest.openSyncSettings() {
        openDrawer()
        clickContentDescriptionScrolled("Settings")
        clickSettingsTab("Sync")
        assertTagDisplayed(TestTags.Sync.INTERVAL_ROW)
    }

    private fun DesktopComposeUiTest.intervalValue(): String? =
        textsOfTag(TestTags.Sync.INTERVAL_ROW).lastOrNull()

    private fun minutesOf(label: String): Int =
        when {
            label.endsWith("min") -> label.removeSuffix("min").trim().toInt()
            label.endsWith("h") -> label.removeSuffix("h").trim().toInt() * 60
            else -> error("unexpected interval label: $label")
        }

    private class FixedSyncScopeProvider(scope: SyncScope) : SyncScopeProvider {
        override val current: Flow<SyncScope?> = MutableStateFlow(scope)
    }
}
