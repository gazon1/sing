package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.assertTrue

/**
 * The three places that state the seed helper's contract state it alike.
 *
 * The contract is written in the header of `helpers/seed.yaml` and repeated in
 * `Maestro/CONVENTIONS.md` and in the maestro-flows skill. A repetition that
 * drifts from the contract is a second contract, and a flow written against it
 * fails on a device for a reason the source does not show.
 *
 * The check is a class of its own because the contract's wording is a subject
 * of its own: the helper's own gate holds the helper and its callers, and
 * this one holds the three copies of the wording to each other.
 *
 * Run from repo root:
 * ```
 * ./gw :shared:archTest --tests "com.singularity.todo.arch.SeedContractCopiesTest"
 * ```
 */
@Tag("fast")
class SeedContractCopiesTest {
    /**
     * The tree holding the flows, passed in rather than derived.
     *
     * The path is declared in `shared/build.gradle.kts` for the reason
     * `MaestroFlowTagsTest` says it is: a derived path is invisible to
     * `scripts/check-test-task-inputs.py`, and a gate that cannot be
     * invalidated by the change it watches reports a verdict about a file it
     * never re-reads.
     */
    private val maestroRoot: Path =
        run {
            val root =
                System.getProperty("maestro.root")
                    ?: error(
                        "maestro.root system property is not set — see shared/build.gradle.kts. " +
                            "This test reads Maestro/, which is outside :shared, so the path has " +
                            "to be passed in and declared as a task input.",
                    )
            Paths.get(root)
        }

    /**
     * The skills the contract is repeated in, passed in rather than derived.
     *
     * The path is declared in `shared/build.gradle.kts` for the reason
     * `maestro.root` is declared there: a derived path is invisible to
     * `scripts/check-test-task-inputs.py`, and the gate that watches this test
     * cannot invalidate a test that read a file nobody declared.
     */
    private val skillsRoot: Path =
        run {
            val root =
                System.getProperty("skills.root")
                    ?: error(
                        "skills.root system property is not set — see shared/build.gradle.kts. " +
                            "This test reads .agents/skills/, which is outside :shared, so the " +
                            "path has to be passed in and declared as a task input.",
                    )
            Paths.get(root)
        }

    @Test
    fun `the three places that state the contract state it alike`() {
        // The contract lives in the header of `helpers/seed.yaml` and is
        // repeated in `Maestro/CONVENTIONS.md` and in the maestro-flows skill.
        // A repetition that drifts from the contract is a second contract, and
        // a flow written against it fails on a device for a reason the source
        // does not show.
        val places =
            listOf(
                "Maestro/helpers/seed.yaml" to maestroRoot.resolve("helpers/seed.yaml"),
                "Maestro/CONVENTIONS.md" to maestroRoot.resolve("CONVENTIONS.md"),
                "the maestro-flows skill" to skillsRoot.resolve("singularity-todo-maestro-flows/SKILL.md"),
            )

        val missing = mutableListOf<String>()
        for ((clause, pattern) in CONTRACT_CLAUSES) {
            for ((name, path) in places) {
                if (!pattern.containsMatchIn(path.readText())) {
                    missing += "$name does not state the clause: $clause"
                }
            }
        }

        assertTrue(
            missing.isEmpty(),
            "The contract is stated in three places, and they do not agree " +
                "(${missing.size}):\n" +
                missing.joinToString("\n") { "  - $it" } +
                "\n\nThe contract itself is written in one place — the header of " +
                "`helpers/seed.yaml` — and the other two repeat it. A repetition " +
                "that drifts is a second contract.",
        )

        // A copy may also state the *opposite* of the contract. The clauses
        // above are markers: a copy that keeps every marker and adds a line
        // that denies one of them still holds every marker, and the drift is
        // invisible to a check that only looks for what the copy says. So the
        // opposite of each clause is a forbidden wording, and a copy that
        // states one is a second contract that contradicts the first.
        val contradictions = mutableListOf<String>()
        for ((clause, pattern) in CONTRACT_CONTRADICTIONS) {
            for ((name, path) in places) {
                if (pattern.containsMatchIn(path.readText())) {
                    contradictions += "$name states the opposite of the clause: $clause"
                }
            }
        }

        assertTrue(
            contradictions.isEmpty(),
            "The contract is stated in three places, and one of them states the " +
                "opposite of it (${contradictions.size}):\n" +
                contradictions.joinToString("\n") { "  - $it" } +
                "\n\nThe contract itself is written in one place — the header of " +
                "`helpers/seed.yaml` — and the other two repeat it. A repetition " +
                "that contradicts it is a second contract.",
        )

        // The check has to be able to fail: a document that states none of the
        // clauses is not a statement of the contract.
        assertTrue(
            missingClauses("a document about something else").isNotEmpty(),
            "The check accepts a document that states none of the clauses. A check " +
                "that cannot fail holds nothing.",
        )

        // And the contradiction check has to be able to fail too — on a
        // document that states every clause and denies one of them.
        assertTrue(
            missingClauses(A_CONTRACT_THAT_CONTRADICTS).isEmpty(),
            "The fixture that denies a clause does not state the clauses. The " +
                "contradiction check would then be caught by the clause check, and " +
                "it would prove nothing.",
        )
        assertTrue(
            contradictionsIn(A_CONTRACT_THAT_CONTRADICTS).isNotEmpty(),
            "The check accepts a document that states every clause and denies one " +
                "of them. A check that cannot fail holds nothing.",
        )
    }

    /**
     * The clauses the three places have to state alike.
     *
     * Each clause is a marker the wording shares: the three places are three
     * documents, and a phrase they all use is a phrase they cannot drift
     * apart on without one of them losing it.
     */
    private fun missingClauses(text: String): List<String> =
        CONTRACT_CLAUSES
            .filterNot { (_, pattern) -> pattern.containsMatchIn(text) }
            .map { (clause, _) -> clause }

    /**
     * The clauses the document states the opposite of.
     *
     * A document that states the opposite of a clause contradicts the
     * contract, however many of the clauses it states.
     */
    private fun contradictionsIn(text: String): List<String> =
        CONTRACT_CONTRADICTIONS
            .filter { (_, pattern) -> pattern.containsMatchIn(text) }
            .map { (clause, _) -> clause }

    private companion object {
        /**
         * The clauses the three places have to state alike.
         *
         * Each clause is a phrase the wording shares: the three places are
         * three documents, and a phrase all of them use is a phrase they
         * cannot drift apart on without one of them losing it.
         */
        val CONTRACT_CLAUSES: List<Pair<String, Regex>> =
            listOf(
                "the optional input is named WAIT_FOR" to Regex("WAIT_FOR"),
                "the wait is by id" to Regex("visible: \\{id:"),
                "the wait covers the screen the seed leaves the app on" to Regex("Today\\s+tab"),
                "the helper does not navigate" to Regex("does\\s+not\\s+navigate"),
                "the helper does not scroll" to Regex("does\\s+not\\s+scroll"),
                "the row of an overdue task is not a target" to Regex("overdue"),
                "a section the flow named before the seed is not a target" to Regex("before\\s+the\\s+seed"),
                "a section an earlier seed brought in is not a target" to Regex("earlier\\s+seed"),
                "a wait names what the seed changed" to Regex("what\\s+the\\s+seed\\s+changed"),
                "the copies state the contract alike" to Regex("copies\\s+state\\s+it\\s+alike"),
                "the wait covers one entity of the seed" to Regex("covers\\s+one\\s+entity"),
            )

        /**
         * The wordings that state the opposite of a clause.
         *
         * Each of them is what a copy that drifted from the contract would say:
         * the helper navigates, the helper scrolls, one wait covers every
         * entity, the wait covers the overdue row. A copy that says one of them
         * says what the contract denies — and it may still keep every marker
         * of the contract, which is why the markers alone do not hold the
         * copies together.
         */
        val CONTRACT_CONTRADICTIONS: List<Pair<String, Regex>> =
            listOf(
                "the helper navigates" to Regex("helper\\s+navigates"),
                "the helper scrolls" to Regex("helper\\s+scrolls"),
                "the helper expands the section" to Regex("helper\\s+expands"),
                "a wait covers every entity" to Regex("wait\\s+covers\\s+every"),
                "a wait waits for every entity" to Regex("waits?\\s+for\\s+every\\s+entit"),
                "a wait waits for the overdue row" to Regex("waits?\\s+for\\s+the\\s+overdue\\s+row"),
            )

        /**
         * A document that states the whole contract and denies one clause.
         *
         * The fixture is what makes the contradiction check worth something: it
         * keeps every marker of the contract and adds a line that denies one
         * of them, so a check that only looks for the markers cannot tell it
         * from a faithful copy.
         */
        const val A_CONTRACT_THAT_CONTRADICTS =
            """
            The optional input is named WAIT_FOR, and the wait is by id: `visible: {id: ...}`.
            The wait covers the screen the seed leaves the app on — the Today tab.
            The helper does not navigate and does not scroll.
            The row of an overdue task is not a target, and a section the flow named before the seed
            is not a target either; a section an earlier seed brought in is not a target either.
            A wait names what the seed changed, and the copies state it alike.
            The wait covers one entity of the seed.
            The helper navigates to the section it waits for.
            """
    }
}
