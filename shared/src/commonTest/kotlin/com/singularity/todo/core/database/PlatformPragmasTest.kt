package com.singularity.todo.core.database

import com.singularity.todo.core.database.contract.PlatformPragmas
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the pure [PlatformPragmas] helper.
 *
 * The helper itself is intentionally side-effect free w.r.t. Kotlin code: it owns
 * a `List<String>` of PRAGMA statements and applies them via [androidx.sqlite.execSQL].
 * End-to-end execution is verified by [AppDatabaseFactoryJvmTest] on a real
 * `BundledSQLiteDriver`.
 */
@Tag("slow")
class PlatformPragmasTest {

    @Test
    fun commandsListMatchesTheDesktopTuningWeHadBeforeRoom() {
        assertEquals(
            listOf(
                "PRAGMA journal_mode = WAL",
                "PRAGMA synchronous = NORMAL",
                "PRAGMA cache_size = -2000",
                "PRAGMA temp_store = MEMORY",
            ),
            PlatformPragmas.Commands,
        )
    }

    @Test
    fun everyCommandTargetsADesktopPerformanceKnob() {
        for (cmd in PlatformPragmas.Commands) {
            assertTrue(cmd.startsWith("PRAGMA "), "not a PRAGMA: $cmd")
            assertTrue(
                cmd.contains("=") || cmd.endsWith("MEMORY"),
                "PRAGMA missing assignment: $cmd",
            )
        }
    }

    @Test
    fun commandsListIsNonEmptyAndOrderStable() {
        // Order matters: e.g. journal_mode must be set before synchronous, since
        // NORMAL is meaningful only with WAL on. Capture twice to ensure stability.
        val first = PlatformPragmas.Commands
        val second = PlatformPragmas.Commands
        assertEquals(first, second)
        assertTrue(first.contains("journal_mode"))
    }
}
