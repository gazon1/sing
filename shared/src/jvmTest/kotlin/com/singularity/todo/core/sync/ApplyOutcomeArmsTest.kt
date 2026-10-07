package com.singularity.todo.core.sync

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The arms [ApplyOutcome] is allowed to have.
 *
 * ## Why a test pins an absence
 *
 * `ApplyOutcome.Conflict` existed for the life of this file and was produced by exactly
 * one thing: a catch block that classified *every* throw out of a repository write as
 * "applied, but a field lost to a newer one". A full disk therefore read as a resolved
 * conflict, the cursor advanced, and the row was never written — the server considered
 * the change delivered and no later cycle re-requested it (`ebb3c1dc`).
 *
 * Once that catch was split correctly, nothing constructed the arm, and `PullSummary.conflicts`
 * became a field that could only ever hold zero. A counter that cannot become non-zero is
 * a lie of the same family the summary used to tell: it reads as "the engine reports
 * conflicts", so nobody goes looking for the missing merge.
 *
 * The arm was also structurally impossible. Applying a pull event is a wholesale upsert of
 * the remote document with no field-level merge, so nothing can be lost to a newer field
 * on this path — the per-field HLC merge that makes that possible lives on the push side.
 *
 * So this asserts the shape rather than a behaviour: three arms, `Conflict` not among
 * them, and a summary that carries no conflict count. If someone adds a real merge to the
 * pull path, this fails and makes them say why — which is the moment the arm should come
 * back.
 *
 * ## Why a scanner and not Konsist
 *
 * Konsist 0.17 does not expose the members of a sealed interface in a way this assertion
 * can use — `interfaces().single { … }.objects()` found nothing and failed at class
 * initialisation. `CachedIdentityReadArchitectureTest` cites the same limitation for
 * lambda bodies and writes a scanner instead, so this follows that precedent.
 *
 * Reading a declaration's shape is not the same as guessing at intent, which is what the
 * house rule against heuristics is about: there is no judgement here about whether some
 * code *means* to report a failure, only about which names the declaration contains.
 */
@Tag("fast")
class ApplyOutcomeArmsTest {

    companion object {
        /** Injected by the jvmTest task config in `shared/build.gradle.kts`. */
        private val commonMainRoot: String = System.getProperty("commonMain.root")
            ?: error(
                "commonMain.root system property is not set — " +
                    "see the jvmTest task config in shared/build.gradle.kts",
            )

        private val syncEngineSource: String = File(
            commonMainRoot,
            "com/singularity/todo/core/sync/SyncEngine.kt",
        ).readText()

        /** The body of a declaration that has braces, up to the `}` that closes it at column 0. */
        private fun bodyOf(declaration: String): String {
            val start = syncEngineSource.indexOf(declaration)
            require(start >= 0) { "$declaration is not in SyncEngine.kt — did it move?" }
            val end = syncEngineSource.indexOf("\n}", start)
            require(end > start) { "$declaration has no closing brace in SyncEngine.kt" }
            return syncEngineSource.substring(start, end)
        }

        /**
         * Constructor parameters of a declaration, read by balancing parentheses.
         *
         * Brace-finding is wrong here: `PullSummary` is declared on one line with no body,
         * so "the next `}` at column 0" is some unrelated declaration further down the file —
         * which is exactly what it collected the first time, sweeping in the members of
         * `SyncOutcome` along with it.
         */
        private fun parametersOf(declaration: String): String {
            val open = syncEngineSource.indexOf(declaration)
            require(open >= 0) { "$declaration is not in SyncEngine.kt — did it move?" }
            val from = syncEngineSource.indexOf('(', open)
            require(from >= 0) { "$declaration has no parameter list" }
            var depth = 0
            var i = from
            while (i < syncEngineSource.length) {
                when (syncEngineSource[i]) {
                    '(' -> depth++

                    ')' -> {
                        depth--
                        if (depth == 0) return syncEngineSource.substring(from + 1, i)
                    }
                }
                i++
            }
            error("$declaration's parameter list is not closed in SyncEngine.kt")
        }

        /** `data object` / `data class` members, one indent level inside the declaration. */
        private fun membersOf(body: String): List<String> =
            Regex("""^\s{4}data (?:object|class)\s+(\w+)""", RegexOption.MULTILINE)
                .findAll(body)
                .map { it.groupValues[1] }
                .toList()

        private val arms: List<String> =
            membersOf(bodyOf("sealed interface ApplyOutcome")).sorted()

        /** Constructor parameters of the pull summary, in declaration order. */
        private val summaryProperties: List<String> =
            Regex("""val\s+(\w+)\s*:""")
                .findAll(parametersOf("data class PullSummary("))
                .map { it.groupValues[1] }
                .toList()
                .sorted()
    }

    @Test
    fun `ApplyOutcome has exactly the three arms that describe the cursor decision`() {
        assertEquals(
            listOf("Applied", "Failed", "Skipped"),
            arms,
            "The arms are not a list of outcomes to report — they are the answer to " +
                "'could trying again ever help', which is what decides the cursor. A new " +
                "arm has to answer that question, and a fourth one means one of the three " +
                "no longer means what this test says it means.",
        )
    }

    @Test
    fun `Conflict is not an outcome this path can produce`() {
        assertFalse(
            "Conflict" in arms,
            "Conflict has come back. It must come back with a reason: the pull path " +
                "upserts the remote document wholesale and merges no fields, so a field " +
                "cannot be lost to a newer one here. If a field-level merge has been " +
                "added to this path, say so in ApplyOutcome's KDoc — and check that " +
                "nothing is manufacturing the arm from a catch block again.",
        )
    }

    @Test
    fun `the pull summary carries no conflict count`() {
        assertFalse(
            "conflicts" in summaryProperties,
            "PullSummary.conflicts is back, and it can only ever be zero. A field that " +
                "reports a number the engine cannot produce reads as a measurement and " +
                "stops anyone looking for the merge that would make it move.",
        )
        assertEquals(
            listOf("applied", "dropped", "received"),
            summaryProperties,
            "The summary says how many arrived, how many are stored, and how many were " +
                "thrown away. That is the whole question it has to answer.",
        )
    }
}
