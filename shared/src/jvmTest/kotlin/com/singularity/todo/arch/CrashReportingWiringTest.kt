package com.singularity.todo.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The corpus and funnel checks for crash reporting.
 *
 * ## What moved to a rule, and why
 *
 * This test used to own three source-text predicates: a ViewModel that can fail must pass
 * a `CrashReportingPort`; a ViewModel must not construct its own scope; and the MVI base
 * must own the reporter. The first two are now
 * `NoUnreportedFailurePath` (detekt-rules), a PSI rule with 15 positive tests.
 *
 * The move was not tidying. The first version of the first predicate fired only on
 * `emitError`/`catchTo` call sites, and it passed for a ViewModel that launched work and
 * handled failures entirely by hand — `ArchiveViewModel`, `TagGroupsViewModel`,
 * `ProjectEditorViewModel`, `SavedAgendaViewModel`, `StatisticsViewModel`, `BackupViewModel`
 * and two more each converted failures into UI state with their own `try`/`catch`/`fold` and
 * reported nothing. Widening a regex would have widened a heuristic, not closed the blind
 * spot, and no arch test can tell the difference: a heuristic that stopped matching is
 * indistinguishable from a codebase that stopped violating. A rule with a positive test
 * can. See `2026-10-05-positive-tests-for-every-detekt-rule` and
 * `2026-10-05-no-direct-dispatchers-rule-was-a-no-op`.
 *
 * ## What stays here, and why it cannot move
 *
 * The two remaining checks are about the *corpus* — the funnel itself, and the scope factory
 * it depends on. They are deliberately the inverse of the rule: the rule asks "does this
 * ViewModel report?", these ask "is there still something for it to report into?" A rule
 * over individual ViewModels is perfectly happy when every one of them routes into a base
 * class that silently does nothing. That is not a per-file property, so it is not a
 * per-file gate, and it is not observable at runtime.
 *
 * Scope: `commonMain` production sources.
 */
@Tag("fast")
class CrashReportingWiringTest {

    companion object {
        /** Injected by the jvmTest task config in `shared/build.gradle.kts`. */
        private val commonMainRoot: String = System.getProperty("commonMain.root")
            ?: error(
                "commonMain.root system property is not set — " +
                    "see the jvmTest task config in shared/build.gradle.kts",
            )

        private val files: List<KoFileDeclaration> =
            Konsist.scopeFromExternalDirectory(commonMainRoot).files

        private fun KoFileDeclaration.codeOnly(): String =
            text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""//[^\n]*"""), "")

        private fun KoFileDeclaration.isViewModel(): Boolean {
            val file = name
            return file == "NotePreview" || file.endsWith("ViewModel")
        }
    }

    @Test
    fun `the funnel itself is wired`() {
        // The base class is where every call site's failure actually lands. If this ever
        // loses its reporter, NoUnreportedFailurePath becomes vacuous for every other file
        // while still passing — which is why the check is here and not in the rule.
        val base = files.single { it.name == "MviViewModel" }.codeOnly()
        assertTrue(
            base.contains("crashReporter"),
            "MviViewModel must own the reporter — catchTo is the single funnel",
        )
    }

    @Test
    fun `the scope factory carries the background failure handler`() {
        // The ViewModel check assumes background work reports somewhere. This is that
        // somewhere, and the only place the assumption is written down.
        val factory = files.single { it.name == "BackgroundScope" }
        assertTrue(
            factory.text.contains("BackgroundFailureHandler"),
            "The expect declaration must document — and the actuals must apply — " +
                "BackgroundFailureHandler, or a failed launch escalates to the platform's " +
                "uncaught-exception handler",
        )
    }

    @Test
    fun `the rule's corpus is a real one`() {
        // A rule that matched nothing would pass for the wrong reason. NoUnreportedFailurePath
        // is configured against the same source tree, so this is also the floor on how much
        // the rule actually sees.
        val viewModels = files.count { it.isViewModel() }
        assertTrue(viewModels > 20, "Expected a real corpus, scanned $viewModels ViewModel files")
    }
}
