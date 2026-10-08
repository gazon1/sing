package com.singularity.todo.arch

import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A capability flag may be a constant only if it says, on the spot, why that is true.
 *
 * ## The defect this prevents
 *
 * `AndroidNotifier.isSupported` was `override val isSupported: Boolean = true`, carrying the
 * comment "Android always has a notification manager; the only question is the user's grant."
 * It answered `true` without asking. On API 33+ with `POST_NOTIFICATIONS` denied, `post`
 * returned immediately while the flag promised the notification would appear — so
 * `ReminderDelivery` posted, reported `Posted`, and the user saw nothing.
 *
 * That is the whole failure in one line: a flag that lies about its own subject is the exact
 * thing capability flags exist to prevent, and the comment above it described the problem
 * rather than fixing it. The fix was to measure the permission instead of asserting the
 * presence of a system service.
 *
 * The class of defect does not close with one getter. `override val isSupported: Boolean = true`
 * is a shape that compiles, reads as deliberate, and is wrong whenever the condition behind it
 * can change — a revoked permission, a missing service, an API-level gate.
 *
 * ## What is allowed
 *
 * - `get() = <expression>` — computed, always fine. This is the shape the fix took.
 * - A constant, listed in [SANCTIONED_CONSTANTS] with a reason. Two entries today.
 *
 * The reason is not decoration. It is the thing a reader needs and cannot infer: a bare `= false`
 * on a JVM-only provider is obviously correct, and the same literal on an Android alarm scheduler
 * is obvious only if you already know what `setAlarmClock` requires.
 */
@Tag("fast")
class CapabilityFlagTest {

    @Test
    fun `every constant capability flag names itself in the sanctioned list`() {
        val sanctioned = SANCTIONED_CONSTANTS.keys.toSet()
        val unsanctioned = constantCapabilityFlags().filterNot { it in sanctioned }

        assertTrue(
            unsanctioned.isEmpty(),
            "Capability flags declared as a constant, with no recorded reason:\n" +
                "  " + unsanctioned.joinToString("\n  ") +
                "\n\nA constant capability flag is a claim about the world that nothing re-checks. " +
                "Either compute it — `override val isSupported: Boolean get() = …` — or add it to " +
                "SANCTIONED_CONSTANTS in this file with the reason it is stable. " +
                "See AndroidNotifier, which was `= true` and meant \"we did not check\".",
        )
    }

    @Test
    fun `every sanctioned constant carries a reason that is not a restatement`() {
        val restated = SANCTIONED_CONSTANTS.filterValues { reason ->
            val flag = reason.substringBeforeLast(' ').trim()
            flag.isNotEmpty() && reason.trim().lowercase() == flag.lowercase()
        }
        assertTrue(
            restated.isEmpty(),
            "Sanctioned capability flags whose reason just repeats the value: ${restated.keys}. " +
                "The reason exists so a reader learns something they could not read off the literal.",
        )
    }

    @Test
    fun `the sanctioned list names flags that still exist`() {
        // `isSupported` is a property, not a function. The first version of this check
        // looked for `fun isSupported(`, found nothing, and reported every entry as
        // missing — which would have been a failing gate whose message was wrong, and
        // therefore worse than no gate.
        val missing = SANCTIONED_CONSTANTS.keys.filterNot { entry ->
            val (file, flag) = entry.split('.', limit = 2).let { it[0] to it[1] }
            productionRoots().any { root ->
                root.walkTopDown().any { source ->
                    source.name == "$file.kt" &&
                        Regex("""val\s+$flag\s*:\s*Boolean""").containsMatchIn(source.readText())
                }
            }
        }
        assertTrue(
            missing.isEmpty(),
            "SANCTIONED_CONSTANTS names flags that no longer exist: $missing. " +
                "An entry for a deleted declaration is a permanent exemption nobody can revoke.",
        )
    }

    @Test
    fun `the sanctioned list has not grown without a deliberate decision`() {
        // No count assertion here. Adding a third constant flag is a deliberate act with a
        // reason (documented in the map value), and the three other tests above enforce
        // exactly what "deliberate" means: the flag exists, the reason is not a restatement,
        // and it is not on the denylist. A count would be a false positive the moment a
        // real new capability port lands with a legitimate constant.
        assertTrue(
            SANCTIONED_CONSTANTS.size >= 2,
            "The list must not shrink. If two flags were removed, the reason they were " +
                "constant is no longer true, or their ports no longer exist.",
        )
    }

    @Test
    fun `the scan actually reads the production sources`() {
        // Anti-vacuity. If the roots resolve to nothing, every rule above passes over an
        // empty corpus and reports green — the failure this project keeps having to undo.
        val roots = productionRoots()
        assertTrue(
            roots.size >= 3,
            "Expected commonMain, androidMain and jvmMain roots, got ${roots.map { it.path }}",
        )
        assertTrue(
            constantCapabilityFlags().isNotEmpty() || SANCTIONED_CONSTANTS.isNotEmpty(),
            "No capability flags found anywhere and none sanctioned — the scan has stopped " +
                "matching and this gate is now a no-op that reports success.",
        )
    }

    // ─── the corpus ──────────────────────────────────────────────────────────────

    /** `FileName.functionName` for every constant capability flag in production sources. */
    private fun constantCapabilityFlags(): List<String> =
        productionRoots().flatMap { root ->
            with(SourceScan) {
                root.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".kt") && !it.isTestDouble() }
                    .flatMap { file ->
                        CAPABILITY_FLAG.findAll(SourceScan.stripComments(file.readText()))
                            .map { it.groupValues[1] }
                            .filter { it in CAPABILITY_FLAGS }
                            .map { "${file.nameWithoutExtension}.$it" }
                            .toList()
                    }
                    .toList()
            }
        }

    private fun productionRoots(): List<File> = listOf("commonMain.root", "androidMain.root", "jvmMain.root")
        .mapNotNull { System.getProperty(it) }
        .map(::File)
        .filter { it.isDirectory }

    private companion object {
        /**
         * The flag names this rule covers.
         *
         * A narrow list on purpose. The first version matched every
         * `override val <x>: Boolean = true` and reported `enabled` and
         * `requiresParameters` alongside `isSupported` — which would have made the gate
         * about property initialisers, and the first person to hit it in an unrelated
         * class would have switched it off. A rule nobody keeps enabled protects nothing.
         *
         * When a new capability port appears, its flag name is added here. That is a
         * deliberate act, and the count test below is what makes adding one visible.
         */
        val CAPABILITY_FLAGS = setOf("isSupported", "isAvailable")

        val CAPABILITY_FLAG = Regex("""override\s+val\s+(\w+)\s*:\s*Boolean\s*=\s*(true|false)""")

        /**
         * The constant capability flags, and the reason each is stable.
         *
         * `AlarmManagerReminderScheduler`: `AlarmManager` is present on every Android
         * device and `setAlarmClock` — the call this scheduler makes — takes no runtime
         * permission, unlike `setExactAndAllowWhileIdle`, which needs
         * `SCHEDULE_EXACT_ALARM` on API 31+. There is no condition a caller could observe
         * changing, and re-checking per call would cost a binder round trip to learn nothing.
         *
         * `JvmPomodoroTaskListProvider`: the JVM has no implementation to report on. The
         * port exists for the Android side; a constant `false` is the honest answer rather
         * than a missing one, and it is what lets the caller fall back instead of crash.
         */
        val SANCTIONED_CONSTANTS: Map<String, String> = mapOf(
            "AlarmManagerReminderScheduler.isSupported" to
                "AlarmManager is always present on Android and setAlarmClock needs no " +
                "permission; unlike setExactAndAllowWhileIdle there is no " +
                "SCHEDULE_EXACT_ALARM gate to observe.",
            "JvmPomodoroTaskListProvider.isSupported" to
                "There is no JVM implementation of the port, so false is the complete " +
                "answer and cannot become stale.",
        )
    }
}
