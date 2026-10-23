package com.singularity.todo.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A proposal's lifecycle is the domain's — and the surfaces do not have opinions
 * about it.
 *
 * ## What the rule is
 *
 * A surface that shows a proposal may not name a proposal item's status. Not
 * because reading a status is dangerous, but because every place that reads one
 * becomes a place that has to be kept in step: the card says "Turned down", the
 * review screen filters by "Pending", the note editor builds a "Pending" item —
 * and each of those is a place where the lifecycle can drift.
 *
 * The domain answers the questions instead: `ProposalItem.outcomeLabel` says what
 * a decided change reads as, `ProposalItem.pending(...)` says what a change that
 * was proposed is, and `AiProposal.pendingItems` says what is still waiting. A
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
            .filter { file -> file.imports.any { it.name.endsWith("ProposalItemStatus") } }
            .map { it.path }

        assertTrue(
            offenders.isEmpty(),
            "A surface must not name ProposalItemStatus — the domain answers what a status " +
                "means. Offenders: $offenders",
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
        // Without this, the two rules above would pass for the wrong reason: a
        // codebase that stopped asking the domain anything would also stop
        // breaking the rules.
        val proposalItem = files.single { it.fileName() == "ProposalItem.kt" }.codeOnly()
        assertTrue(
            proposalItem.contains("outcomeLabel"),
            "ProposalItem must say what a decided change reads as — the surface prints it",
        )
        assertTrue(
            proposalItem.contains("fun pending("),
            "ProposalItem must offer the change that waits for a decision — the surface builds it",
        )
    }
}
