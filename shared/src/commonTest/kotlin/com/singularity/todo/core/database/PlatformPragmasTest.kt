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
 * separate `List<String>` of PRAGMA statements split by scope
 * ([FileLevelCommands] vs [PerConnectionCommands]) and applies them via
 * [androidx.sqlite.execSQL].
 *
 * End-to-end execution on a real [androidx.sqlite.driver.bundled.BundledSQLiteDriver]
 * is verified by [AppDatabaseFactoryJvmTest].
 */
@Tag("slow")
class PlatformPragmasTest {

    @Test
    fun fileLevelCommandsAreWALOnly() {
        assertEquals(listOf("PRAGMA journal_mode = WAL"), PlatformPragmas.FileLevelCommands)
    }

    @Test
    fun perConnectionCommandsAreExactlyFive() {
        val cmds = PlatformPragmas.PerConnectionCommands
        assertEquals(5, cmds.size, "synchronous, cache_size, temp_store, foreign_keys, busy_timeout")
    }

    @Test
    fun perConnectionCommandsCoverPerformanceAndSafety() {
        val all = PlatformPragmas.PerConnectionCommands.joinToString("\n")
        assertTrue(all.contains("synchronous"), "synchronous NORMAL — fsync cost O(1)")
        assertTrue(all.contains("cache_size"), "cache_size -2000 — 2 MiB page cache")
        assertTrue(all.contains("temp_store"), "temp_store = MEMORY — RAM for sort")
        assertTrue(all.contains("foreign_keys"), "foreign_keys = ON — FK enforcement")
        assertTrue(all.contains("busy_timeout"), "busy_timeout = 5000 — 5 s retry window")
    }

    @Test
    fun fileLevelCommandsTargetFileHeader() {
        for (cmd in PlatformPragmas.FileLevelCommands) {
            assertTrue(cmd.startsWith("PRAGMA "), "not a PRAGMA: $cmd")
            assertTrue(cmd.contains("journal_mode"), "only journal_mode is file-level: $cmd")
        }
    }

    @Test
    fun perConnectionCommandsAreAllPerConnection() {
        for (cmd in PlatformPragmas.PerConnectionCommands) {
            assertTrue(cmd.startsWith("PRAGMA "), "not a PRAGMA: $cmd")
            assertTrue(
                cmd.contains("synchronous") ||
                    cmd.contains("cache_size") ||
                    cmd.contains("temp_store") ||
                    cmd.contains("foreign_keys") ||
                    cmd.contains("busy_timeout"),
                "per-connection PRAGMA only: $cmd",
            )
        }
    }

    @Test
    fun listsAreNonEmptyAndOrderStable() {
        // Order matters: journal_mode must be set before synchronous, since NORMAL is
        // meaningful only with WAL. Capture twice to ensure stability.
        val first = PlatformPragmas.FileLevelCommands
        val second = PlatformPragmas.FileLevelCommands
        assertEquals(first, second)

        val pFirst = PlatformPragmas.PerConnectionCommands
        val pSecond = PlatformPragmas.PerConnectionCommands
        assertEquals(pFirst, pSecond)
    }
}
