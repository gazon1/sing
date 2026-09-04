package com.singularity.todo.core.database

import com.singularity.todo.core.database.contract.PlatformPragmas
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
class PlatformPragmasTest {

    @Test
    fun `Commands list matches the desktop tuning we had before Room`() {
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
    fun `every command targets a desktop performance knob`() {
        for (cmd in PlatformPragmas.Commands) {
            assertTrue(cmd.startsWith("PRAGMA "), "not a PRAGMA: $cmd")
            assertTrue(
                cmd.contains("=") || cmd.endsWith("MEMORY"),
                "PRAGMA missing assignment: $cmd",
            )
        }
    }

    @Test
    fun `Commands list is non-empty and order-stable`() {
        // Order matters: e.g. journal_mode must be set before synchronous, since
        // NORMAL is meaningful only with WAL on. Capture twice to ensure stability.
        val first = PlatformPragmas.Commands
        val second = PlatformPragmas.Commands
        assertEquals(first, second)
        assertTrue(first.isNotEmpty())
    }
}