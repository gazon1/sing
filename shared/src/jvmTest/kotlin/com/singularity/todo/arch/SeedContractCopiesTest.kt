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
 * What the check is, precisely: the copies have to state the same *clauses*.
 * A clause is a consequence of the contract a flow author relies on, and it is
 * held by a marker — a phrase all three copies use. The check is not a
 * comparison of the three texts: a copy may state a clause in its own words.
 * What it holds is that no copy loses a clause and no copy contradicts one,
 * because a copy that drops a rule or denies it is a second contract. The
 * clauses below are chosen for that: each of them is a rule that changes what
 * a flow has to write, not a wording.
 *
 * A marker has to be as specific as the clause it holds. A marker that matches
 * a word the copy also uses elsewhere — "overdue" — is satisfied by a copy that
 * lost the rule and kept the word, and the drift is invisible. So every marker
 * here is a phrase the clause's own statement uses, and the last test holds the
 * three copies to the same number of occurrences of it: a copy that gains a
 * mention of the same words outside the clause shows up there.
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

    /**
     * The three places that state the contract.
     *
     * The contract lives in the header of `helpers/seed.yaml` and is repeated
     * in `Maestro/CONVENTIONS.md` and in the maestro-flows skill.
     */
    private fun places(): List<Pair<String, Path>> =
        listOf(
            "Maestro/helpers/seed.yaml" to maestroRoot.resolve("helpers/seed.yaml"),
            "Maestro/CONVENTIONS.md" to maestroRoot.resolve("CONVENTIONS.md"),
            "the maestro-flows skill" to skillsRoot.resolve("singularity-todo-maestro-flows/SKILL.md"),
        )

    /**
     * The text a copy states the contract in, with the markup taken off.
     *
     * The contract is a comment in one copy and prose in the other two, and it
     * is wrapped differently in each. The markers are phrases of the statement
     * itself, so they have to be matched against the words and not against the
     * way the words are laid out: the comment prefix and the line breaks say
     * nothing about which clause a copy states.
     */
    private fun normalized(text: String): String =
        text
            .replace(Regex("(?m)^[ \\t]*#+[ \\t]?"), " ")
            .replace(Regex("\\s+"), " ")

    @Test
    fun `the three places that state the contract state it alike`() {
        val missing = mutableListOf<String>()
        for ((clause, pattern) in CONTRACT_CLAUSES) {
            for ((name, path) in places()) {
                if (!pattern.containsMatchIn(normalized(path.readText()))) {
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
            for ((name, path) in places()) {
                if (pattern.containsMatchIn(normalized(path.readText()))) {
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
     * A clause's marker is as common in the three copies as the clause is.
     *
     * A marker that matches a word the copy uses elsewhere — "overdue", or
     * "WAIT_FOR" — is satisfied by a copy that dropped the rule and kept the
     * word, and the drift the clause check is there to catch goes unseen. The
     * three copies state a clause the same number of times, so a copy that
     * gains a mention of the same words outside the clause, or loses one of
     * the statements of it, shows up here: the counts stop matching.
     */
    @Test
    fun `a clause's marker is as common in the three copies as the clause is`() {
        val mismatched = mutableListOf<String>()
        for ((clause, pattern) in CONTRACT_CLAUSES) {
            val counts = places().map { (_, path) -> pattern.findAll(normalized(path.readText())).count() }
            if (counts.any { it == 0 }) {
                mismatched += "no copy states the clause by its marker: $clause"
            } else if (counts.distinct().size != 1) {
                mismatched +=
                    "the marker of the clause occurs a different number of times in " +
                    "the copies ($counts): $clause"
            }
        }

        assertTrue(
            mismatched.isEmpty(),
            "A clause's marker has to be as specific as the clause it holds " +
                "(${mismatched.size}):\n" +
                mismatched.joinToString("\n") { "  - $it" } +
                "\n\nA marker that matches a word the copy also uses elsewhere is " +
                "satisfied by a copy that lost the rule and kept the word. Give the " +
                "clause a phrase of its own statement instead.",
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
         * Each clause is a phrase the wording shares: the three places are three
         * documents, and a phrase all of them use is a phrase they cannot drift
         * apart on without one of them losing it.
         *
         * Each marker is a phrase of the statement that holds the clause, not
         * a word the clause is about: a marker of the statement cannot be
         * satisfied by a paragraph that merely mentions the same words.
         */
        val CONTRACT_CLAUSES: List<Pair<String, Regex>> =
            listOf(
                "the optional input is named WAIT_FOR" to
                    Regex("WAIT_FOR\\s+is\\s+optional|`WAIT_FOR`\\s+is\\s+the\\s+element\\s+id"),
                "the wait is by id" to Regex("visible: \\{id: …\\}"),
                "the wait covers the screen the seed leaves the app on" to
                    Regex("comes\\s+up\\s+on\\s+the\\s+Today\\s+tab|agenda's\\s+Today\\s+tab"),
                "the helper does not navigate" to Regex("does\\s+not\\s+navigate"),
                "the helper does not scroll" to
                    Regex(
                        "neither\\s+expands\\s+the\\s+section\\s+nor\\s+scrolls|and\\s+the\\s+helper\\s+does\\s+not\\s+scroll",
                    ),
                "the row of an overdue task is not a target" to
                    Regex("row\\s+of\\s+an\\s+overdue\\s+task|not\\s+by\\s+its\\s+row"),
                "a section the flow named before the seed is not a target" to
                    Regex("before\\s+the\\s+seed\\s+was\\s+there\\s+before\\s+the\\s+seed"),
                "a section an earlier seed brought in is not a target" to
                    Regex(
                        "The\\s+same\\s+holds\\s+for\\s+a\\s+section\\s+an\\s+earlier\\s+seed|brought\\s+in\\s+is\\s+the\\s+same\\s+case",
                    ),
                "a wait names what the seed changed" to
                    Regex("to\\s+name\\s+what\\s+the\\s+seed\\s+changed"),
                "the copies state the contract alike" to Regex("copies\\s+state\\s+it\\s+alike"),
                "the wait covers one entity of the seed" to
                    Regex("The\\s+wait\\s+covers\\s+one\\s+entity\\s+of\\s+that\\s+seed"),
                "the wait names what the flow asserts on" to
                    Regex("names\\s+the\\s+entity\\s+the\\s+flow\\s+asserts\\s+on"),
                "the wait belongs to the seed that asked for it" to
                    Regex("belongs\\s+to\\s+the\\s+seed\\s+that\\s+asked\\s+for\\s+it"),
                "the shell wait and the entity wait are two waits" to
                    Regex(
                        "shell\\s+wait\\s+lets\\s+the\\s+app\\s+come\\s+up|" +
                            "shell\\s+wait\\s+stands\\s+between\\s+the\\s+link\\s+and\\s+the",
                    ),
                "the wait is the entity wait" to Regex("absorbs\\s+the\\s+arrival"),
                "a seed that writes no entity waits for the shell alone" to
                    Regex(
                        "gets\\s+the\\s+shell\\s+wait\\s+alone|the\\s+helper\\s+waits\\s+for\\s+the\\s+shell\\s+alone",
                    ),
                "a title-named entity is named by the id the app renders" to
                    Regex("rule\\s+is\\s+`TestTags\\.slug`|\\(`TestTags\\.slug`\\)"),
                "a single named entity is waited for by the helper" to
                    Regex("single\\s+named\\s+entity"),
                "a title-named entity is a case of its own" to Regex("A\\s+title-named\\s+entity"),
                "an anonymous session writes no entity" to Regex("anonymous\\s+session"),
                "several rows in one seed are the flow's to wait for" to
                    Regex("Several\\s+rows\\s+in\\s+one\\s+seed"),
                "an entity that is not on the screen is the flow's to wait for" to
                    Regex("not\\s+on\\s+the\\s+screen\\s+the\\s+seed\\s+leaves\\s+the\\s+app\\s+on"),
                "a flow that keeps a wait says why in a comment" to
                    Regex("keeps\\s+its\\s+own\\s+wait\\s+and\\s+says\\s+why"),
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
            The optional input is named WAIT_FOR: WAIT_FOR is optional, and the wait is by id —
            `visible: {id: …}`.
            The app comes up on the Today tab, so the wait covers the screen the seed leaves the app on.
            The helper does not navigate, and it neither expands the section nor scrolls.
            The row of an overdue task is not a target, and a section the flow named before the seed
            was there before the seed is not a target either; The same holds for a section an earlier
            seed brought in: it is not a target either.
            A wait has to name what the seed changed, and the three copies state it alike.
            The wait covers one entity of that seed.
            The wait names the entity the flow asserts on, and it belongs to the seed that asked for it.
            The shell wait stands between the link and the entity wait: it lets the app come up,
            and the entity wait absorbs the arrival.
            A seed that writes no entity gets the shell wait alone.
            A title-named entity is named by the id the app renders — the rule is `TestTags.slug`.
            The five cases, one by one: a single named entity is waited for by the helper;
            a title-named entity is named by the element its title produces;
            an anonymous session writes no entity at all;
            Several rows in one seed are the flow's to wait for;
            an entity that is not on the screen the seed leaves the app on is the flow's to wait for;
            a flow that keeps its own wait and says why in a comment.
            The helper navigates to the section it waits for.
            """
    }
}
