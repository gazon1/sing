package com.singularity.todo.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.declaration.KoImportDeclaration
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A proposal's lifecycle is the domain's — and the surfaces do not have opinions
 * about it.
 *
 * ## What the rule is
 *
 * A surface that shows a proposal may not name a proposal item's status, nor a
 * proposal's own status. Not because reading a status is dangerous, but because
 * every place that reads one becomes a place that has to be kept in step: the card
 * says "Turned down", the review screen filters by "Pending", the note editor
 * builds a "Pending" item — and each of those is a place where the lifecycle can
 * drift.
 *
 * The domain answers the questions instead: `ProposalItem.outcomeLabel` says what
 * a decided change reads as, `ProposalItem.pending(...)` says what a change that
 * was proposed is, `AiProposal.pendingItems` says what is still waiting, and
 * `AiProposal.isWaiting` says whether a proposal is still waiting for the user. A
 * surface prints the answer; it does not derive one.
 *
 * ## Why the styles are in the same rule
 *
 * The wording of a decision is the same kind of thing: a value three surfaces
 * share, which used to live in three ViewModels and one coordinator. Now it lives
 * in `ProposalDecisionStyles`, and this rule says so — a style declared in a
 * ViewModel is a style that the other two surfaces do not have.
 *
 * ## Why a positive test
 *
 * A rule that matches nothing passes for the wrong reason. So the third test
 * asserts that the domain really does answer — the wording and the factory are
 * there, and they are where the surfaces go for them. That is the difference
 * between a rule with a corpus and a rule with an empty one.
 *
 * Scope: `shared/src/commonMain/kotlin` production sources.
 */
@Tag("slow")
@Tag("slow:konsist")
class ProposalLifecycleStaysInTheDomainTest {

    companion object {

        /**
         * The names a surface may not use to talk about a status.
         *
         * The type and the two predicates: asking "is this change waiting?" is
         * naming the status, in the same way spelling the type is.
         */
        val FORBIDDEN_STATUS_NAMES = setOf("ProposalStatus", "ProposalItemStatus")

        /** The predicates that ask about a status without naming the type. */
        val FORBIDDEN_PREDICATE_NAMES = setOf("isPending", "isDecided")

        /**
         * The name of an import, without the package it came from.
         *
         * Konsist hands out the fully qualified name, so a comparison with a short
         * name never matches — and a rule that cannot match is a rule that does not
         * hold.
         */
        private fun KoImportDeclaration.shortName(): String = name.substringAfterLast('.')

        /** Injected by the jvmTest task config in `shared/build.gradle.kts`. */
        private val commonMainRoot: String = System.getProperty("commonMain.root")
            ?: error(
                "commonMain.root system property is not set — " +
                    "see the jvmTest task config in shared/build.gradle.kts",
            )

        private val files: List<KoFileDeclaration> =
            Konsist.scopeFromExternalDirectory(commonMainRoot).files

        /** A surface: a file under a `presentation` package directory. */
        private fun KoFileDeclaration.isSurface(): Boolean =
            path.replace('\\', '/').contains("/presentation/")

        private fun KoFileDeclaration.fileName(): String =
            path.replace('\\', '/').substringAfterLast('/')

        private fun KoFileDeclaration.codeOnly(): String = KotlinText.stripComments(text)
    }

    @Test
    fun `no surface names a proposal item's status`() {
        val offenders = files
            .filter { it.isSurface() }
            .filter { file -> file.imports.any { it.shortName() in FORBIDDEN_STATUS_NAMES } }
            .map { it.path }

        assertTrue(
            offenders.isEmpty(),
            "A surface must not name ProposalItemStatus — the domain answers what a status " +
                "means. Offenders: $offenders",
        )
    }

    @Test
    fun `no surface names a proposal's status`() {
        // The same rule one level up. A proposal's status used to be a column of its
        // own, and a surface could name it: the review screen asked the row for
        // "pending" and filtered the answers by the changes, the note editor built
        // a "pending" proposal, and the task hub did the same. Three places where
        // the lifecycle of a proposal was a matter of opinion.
        //
        // The status is now what the changes say it is, so there is nothing for a
        // surface to name: the proposal answers "is anything still waiting" and the
        // surface asks it.
        val offenders = files
            .filter { it.isSurface() }
            .filter { file -> file.imports.any { it.shortName() in FORBIDDEN_STATUS_NAMES } }
            .map { it.path }

        assertTrue(
            offenders.isEmpty(),
            "A surface must not name ProposalStatus — the domain answers what a proposal's " +
                "status means. Offenders: $offenders",
        )
    }

    @Test
    fun `no surface asks a status predicate either`() {
        // The predicates are the same question in a shorter form: "is this change
        // waiting?" and "has this change been decided?". A surface that imports one
        // of them is naming the status — it just does it without spelling the type.
        // So the rule covers them too, or it covers a rename of the type and nothing
        // else.
        val offenders = files
            .filter { it.isSurface() }
            .filter { file -> file.imports.any { it.shortName() in FORBIDDEN_PREDICATE_NAMES } }
            .map { it.path }

        assertTrue(
            offenders.isEmpty(),
            "A surface must not ask whether a change is pending or decided — the domain " +
                "answers that. Offenders: $offenders",
        )
    }

    @Test
    fun `the wording of a decision is declared in one place`() {
        val offenders = files
            .filter { it.fileName() != "ProposalDecisionStyle.kt" }
            .filter { it.fileName() != "ProposalDecisionStyles.kt" }
            .filter { file -> file.codeOnly().contains("ProposalDecisionStyle(") }
            .map { it.path }

        assertTrue(
            offenders.isEmpty(),
            "A decision style is a value three surfaces share — it belongs in " +
                "ProposalDecisionStyles, not in a ViewModel or a coordinator. Offenders: $offenders",
        )
    }

    @Test
    fun `the domain really does answer the questions the surfaces ask`() {
        // Without this, the rules above would pass for the wrong reason: a
        // codebase that stopped asking the domain anything would also stop
        // breaking the rules. So the test says what the domain must offer — the
        // wording, the factory, and the two questions a surface asks.
        val proposalItem = files.single { it.fileName() == "ProposalItem.kt" }.codeOnly()
        assertTrue(
            proposalItem.contains("outcomeLabel"),
            "ProposalItem must say what a decided change reads as — the surface prints it",
        )
        assertTrue(
            proposalItem.contains("fun pending("),
            "ProposalItem must offer the change that waits for a decision — the surface builds it",
        )

        val proposal = files.single { it.fileName() == "AiProposal.kt" }.codeOnly()
        assertTrue(
            proposal.contains("isWaiting"),
            "AiProposal must say whether anything in it still waits — the surface asks it",
        )
        assertTrue(
            proposal.contains("val status: ProposalStatus"),
            "AiProposal must derive its status from its changes — the surface never declares one",
        )

        // And the question has to reach the surface: the port is how a surface
        // asks it, so the port has to offer it.
        val port = files.single { it.fileName() == "ProposalRepository.kt" }.codeOnly()
        assertTrue(
            port.contains("watchWaitingProposals"),
            "The port must offer the waiting proposals — it is how a surface asks the domain",
        )
    }
}
