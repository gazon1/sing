package com.singularity.todo.arch

import com.singularity.todo.feature.agenda.data.CrossUserWriteRegistry
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the sanctioned cross-user write list.
 *
 * There is no rule that *catches* a new cross-user write — `CrossUserWriteRegistry`
 * documents why a syntactic one is not buildable at a usable noise level, and the
 * measurement behind that decision is in its KDoc. This test guards what a registry
 * can guard without a rule:
 *
 * 1. Every entry names a method that still exists. A registry of deleted methods
 *    is worse than none: it reads as a live list of approved holes.
 * 2. The list has not quietly grown. Each new entry is a new hole in
 *    `assertCanWrite`; the count is pinned so growing it is a deliberate,
 *    visible act rather than a drive-by addition.
 * 3. Every method that bypasses the guard says so in its own KDoc, so someone
 *    reading the write — not the registry — learns that it is an exception.
 */
class CrossUserWriteRegistryTest {

    @Test
    fun `every sanctioned cross-user write names a method that still exists`() {
        val missing = CrossUserWriteRegistry.sanctioned.filterNot { entry ->
            val (file, method) = entry.split('.', limit = 2).let { it[0] to it[1] }
            val source = File(commonMainRoot).walkTopDown().firstOrNull { it.name == "$file.kt" }
            source != null && Regex("""fun\s+$method\s*\(""").containsMatchIn(source.readText())
        }
        assertTrue(
            missing.isEmpty(),
            "CrossUserWriteRegistry lists methods that no longer exist: ${missing.joinToString()}. " +
                "A registry of deleted methods reads as a live list of approved holes — remove them.",
        )
    }

    @Test
    fun `the sanctioned list has not grown without a deliberate decision`() {
        assertEquals(
            1,
            CrossUserWriteRegistry.sanctioned.size,
            "Each entry is a hole in assertCanWrite. Bump this number only alongside the reason " +
                "the guard does not apply, and record it in docs/decisions/deferred-backlog.md. " +
                "Currently sanctioned: ${CrossUserWriteRegistry.sanctioned.joinToString()}",
        )
    }

    @Test
    fun `every sanctioned write documents the bypass where it happens`() {
        val undocumented = CrossUserWriteRegistry.sanctioned.filterNot { entry ->
            val (file, method) = entry.split('.', limit = 2).let { it[0] to it[1] }
            val source = File(commonMainRoot).walkTopDown().firstOrNull { it.name == "$file.kt" }
            val text = source?.readText() ?: return@filterNot false
            val start = text.indexOf("fun $method(")
            if (start < 0) return@filterNot false
            // The KDoc immediately above the declaration has to mention the guard.
            val before = text.substring((start - 700).coerceAtLeast(0), start)
            before.contains("assertCanWrite")
        }
        if (undocumented.isNotEmpty()) {
            fail(
                "Sanctioned cross-user writes whose KDoc does not mention assertCanWrite: " +
                    undocumented.joinToString() +
                    "\nA reader at the call site must learn that this bypasses the guard, " +
                    "without having to know the registry exists.",
            )
        }
    }

    private companion object {
        val commonMainRoot: String = System.getProperty("commonMain.root")
            ?: error("commonMain.root is not set — see the jvmTest config in shared/build.gradle.kts")
    }
}
