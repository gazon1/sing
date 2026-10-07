package com.singularity.todo.arch

import com.singularity.todo.feature.reminders.data.CrossProfileReadRegistry
import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the sanctioned cross-profile read list, and makes it impossible for it to rot.
 *
 * ## Why this has to exist
 *
 * `ReminderDao.watchAllProfiles()` is the one query in `task_reminders` with no `user_id`
 * filter. It exists because arming OS alarms is a device-wide operation driven by a
 * device-wide event, and scoping it to whoever happened to be logged in meant a reminder
 * for a second profile never fired at all.
 *
 * That reasoning is recorded in the registry. This test is what makes the record binding:
 *
 * 1. Every entry names a method that still exists. A registry of deleted methods is worse
 *    than none — it reads as a live list of approved holes.
 * 2. The list has not quietly grown. Each entry is a new hole in profile isolation, so
 *    growing it takes a visible act and a reason, not a drive-by line.
 * 3. Every sanctioned read documents itself at the call site, so someone reading the query
 *    — not the registry — learns that it crosses a profile boundary.
 *
 * The query-level counterpart is [ScopedReadQueryIsolationTest], which is what *finds* an
 * unfiltered read. This test is what keeps the answer to "why is that one allowed?" honest.
 *
 * Note the registry's own KDoc used to claim this test existed. It did not. That is the
 * exact defect the two WS4 bugs shared — documentation asserting a guard that was not
 * there — and it is worth naming that it survived review twice.
 */
@Tag("fast")
class CrossProfileReadRegistryTest {

    @Test
    fun `every sanctioned cross-profile read names a method that still exists`() {
        val missing = CrossProfileReadRegistry.sanctioned.filterNot { entry ->
            val (iface, method) = entry.split('.', limit = 2).let { it[0] to it[1] }
            val source = daosSource().readText()
            Regex("""fun\s+$method\s*\(""").containsMatchIn(source) &&
                Regex("""interface\s+$iface\b""").containsMatchIn(source)
        }
        assertTrue(
            missing.isEmpty(),
            "CrossProfileReadRegistry lists methods that no longer exist: ${missing.joinToString()}. " +
                "A registry of deleted methods reads as a live list of approved holes — remove them.",
        )
    }

    @Test
    fun `the sanctioned list has not grown without a deliberate decision`() {
        assertEquals(
            1,
            CrossProfileReadRegistry.sanctioned.size,
            "Each entry is a hole in profile isolation. Bump this number only alongside the " +
                "reason the isolation does not apply, and record it in docs/decisions/. " +
                "Currently sanctioned: ${CrossProfileReadRegistry.sanctioned.joinToString()}",
        )
    }

    @Test
    fun `every sanctioned read documents the boundary it crosses`() {
        val undocumented = CrossProfileReadRegistry.sanctioned.filterNot { entry ->
            val (_, method) = entry.split('.', limit = 2).let { it[0] to it[1] }
            val text = daosSource().readText()
            val start = text.indexOf("fun $method(")
            if (start < 0) return@filterNot false
            // The KDoc immediately above the declaration has to name what it is doing.
            val before = text.substring((start - 900).coerceAtLeast(0), start)
            before.contains("profile", ignoreCase = true)
        }
        if (undocumented.isNotEmpty()) {
            throw AssertionError(
                "Sanctioned cross-profile reads whose KDoc does not explain the boundary: " +
                    undocumented.joinToString() +
                    "\nA reader at the call site must learn that this crosses profiles, " +
                    "without having to know the registry exists.",
            )
        }
    }

    @Test
    fun `the sanctioned method is still the one the re-arm path uses`() {
        // Not a tautology: `watchAllProfiles` exists, `sanctioned` names it, and the
        // registry could list a method nobody calls — an approved hole that no longer
        // needs approving. The re-arm is the reason this entry exists, so pin the caller.
        val repository = SourceScan.commonMainRoot()
            .walkTopDown()
            .first { it.name == "ReminderRepositoryImpl.kt" }
            .readText()
        assertTrue(
            repository.contains("watchAllProfiles()"),
            "Nothing calls watchAllProfiles any more, so the sanctioned hole can be removed " +
                "and both re-arm paths can go back to the profile-scoped query.",
        )
    }

    private fun daosSource(): File {
        val root = SourceScan.commonMainRoot()
        val direct = root.resolve("core/database/Daos.kt")
        if (direct.isFile) return direct
        return root.walkTopDown().firstOrNull { it.name == "Daos.kt" }
            ?: error("Daos.kt not found under $root — the source root this gate reads has moved")
    }
}
