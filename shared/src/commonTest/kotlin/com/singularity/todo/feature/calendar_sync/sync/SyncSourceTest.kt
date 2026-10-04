package com.singularity.todo.feature.calendar_sync.sync

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("fast")
class SyncSourceTest {

    // ── upgrade() truth table ───────────────────────────────────────────────────

    /** showIndicator beats non-showIndicator, regardless of immediate flag. */
    @Test
    fun upgrade_showIndicator_wins_over_nonShowIndicator() {
        val result = SyncSource.ConfigChanged.upgrade(SyncSource.TaskDirty)
        assertEquals(SyncSource.ConfigChanged, result)
    }

    @Test
    fun upgrade_nonShowIndicator_wins_when_other_showsIndicator() {
        val result = SyncSource.TaskDirty.upgrade(SyncSource.ConfigChanged)
        assertEquals(SyncSource.ConfigChanged, result)
    }

    @Test
    fun upgrade_both_showIndicator_picks_immediate() {
        val result = SyncSource.Manual.upgrade(SyncSource.ConfigChanged)
        assertEquals(SyncSource.Manual, result) // Manual: immediate=true, ConfigChanged: immediate=true → first wins
    }

    @Test
    fun upgrade_both_nonShowIndicator_picks_immediate() {
        val result = SyncSource.Periodic.upgrade(SyncSource.AppResumed)
        assertEquals(SyncSource.AppResumed, result) // Periodic: immediate=false, AppResumed: immediate=false → first wins
    }

    @Test
    fun upgrade_same_source_returns_self() {
        assertEquals(SyncSource.TaskDirty, SyncSource.TaskDirty.upgrade(SyncSource.TaskDirty))
        assertEquals(SyncSource.Manual, SyncSource.Manual.upgrade(SyncSource.Manual))
        assertEquals(SyncSource.ConfigChanged, SyncSource.ConfigChanged.upgrade(SyncSource.ConfigChanged))
    }

    @Test
    fun upgrade_taskDirty_vs_periodic_immediate_wins() {
        // TaskDirty: showIndicator=true, immediate=false
        // Periodic: showIndicator=false, immediate=false
        // → showIndicator wins (TaskDirty)
        val result = SyncSource.TaskDirty.upgrade(SyncSource.Periodic)
        assertEquals(SyncSource.TaskDirty, result)
    }

    @Test
    fun upgrade_manual_vs_configChanged_both_immediate_showIndicator() {
        // Manual: showIndicator=true, immediate=true
        // ConfigChanged: showIndicator=true, immediate=true
        // → first wins (Manual)
        val result = SyncSource.Manual.upgrade(SyncSource.ConfigChanged)
        assertEquals(SyncSource.Manual, result)
    }
}
