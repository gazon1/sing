package com.singularity.todo.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A ViewModel that can fail but never passes a `CrashReportingPort` inherits
 * `MviViewModel`'s no-op default, so its failures reach the UI and nothing else. The code
 * compiles, the ViewModel is fully wired, and the reporting silently does nothing — the
 * "implemented but unwired" failure mode this project audits for, arriving through a
 * defaulted parameter instead of a missing `single {}`.
 *
 * This is a structural check rather than a behavioural one: "reported nothing" is the
 * absence of an effect and cannot be observed from outside. So the invariant is asserted
 * on source shape instead.
 *
 * ## Why the trigger is broad
 *
 * The first version of this guard fired only on `emitError`/`catchTo` call sites, and it
 * passed for a ViewModel that launched work and handled failures entirely by hand. That is
 * not hypothetical: `ArchiveViewModel`, `TagGroupsViewModel`, `ProjectEditorViewModel`,
 * `SavedAgendaViewModel`, `StatisticsViewModel` and `BackupViewModel` all converted failures
 * into UI state or events with their own `try`/`catch`/`fold`, and every one of them reported
 * nothing. A guard that only recognises the funnel it helped create cannot see a call site
 * that routes around the funnel.
 *
 * So the trigger is "can this class fail at all" — it uses the funnel, launches a coroutine,
 * or catches something — rather than "does it use the funnel". Being strict about the shape
 * is the point: a ViewModel that can fail must have somewhere to report to.
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

        /** Strips KDoc and line comments so prose about the funnel is not read as a call. */
        private fun KoFileDeclaration.codeOnly(): String =
            text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""//[^\n]*"""), "")

        private fun KoFileDeclaration.isViewModel(): Boolean {
            val file = name
            return file == "NotePreview" || file.endsWith("ViewModel")
        }

        /** Uses the MVI error funnel. */
        private fun canFail(code: String): Boolean = code.contains("emitError(") || code.contains("catchTo(")

        /** Starts a coroutine, whose body can throw into the scope's failure handler. */
        private fun launchesWork(code: String): Boolean =
            Regex("""\blaunch\s*[({]""").containsMatchIn(code) || code.contains(".launch")

        /** Catches a failure by hand instead of routing it through the funnel. */
        private fun catchesByHand(code: String): Boolean =
            code.contains("runCatching") ||
                code.contains(".catch {") ||
                Regex("""\bcatch\s*\(""").containsMatchIn(code)
    }

    @Test
    fun `every viewmodel that can fail wires a crash reporter`() {
        val offenders = files
            .filter { it.isViewModel() }
            .map { it to it.codeOnly() }
            .filter { (_, code) -> canFail(code) || launchesWork(code) || catchesByHand(code) }
            .filterNot { (_, code) -> code.contains("crashReporter") }
            .map { (file, _) -> file.name }

        if (offenders.isNotEmpty()) {
            fail(
                "These ViewModels can fail but never pass a CrashReportingPort, so their " +
                    "failures are silently dropped:\n  " + offenders.joinToString("\n  ") +
                    "\nAdd `crashReporter: CrashReportingPort` to the constructor (before " +
                    "`scope`) and `crashReporter = get()` to the Koin binding.",
            )
        }
    }

    @Test
    fun `no viewmodel builds a scope that bypasses the shared failure handler`() {
        // Every scope from createBackgroundScope() carries BackgroundFailureHandler, which is
        // what turns a failed launch into a report instead of a killed Android process. A
        // hand-rolled scope is a hole straight back through it, and it compiles silently.
        val bypasses = files
            .filter { it.isViewModel() }
            .map { it to it.codeOnly() }
            .filter { (_, code) ->
                code.contains("GlobalScope") ||
                    code.contains("MainScope(") ||
                    Regex("""\bCoroutineScope\s*\(""").containsMatchIn(code)
            }
            .map { (file, _) -> file.name }

        if (bypasses.isNotEmpty()) {
            fail(
                "These ViewModels construct a scope directly instead of taking the injected " +
                    "AutoCloseableCoroutineScope, so their background failures skip " +
                    "BackgroundFailureHandler:\n  " + bypasses.joinToString("\n  "),
            )
        }
    }

    @Test
    fun `the funnel itself is wired`() {
        // The base class is where every call site's failure actually lands. If this ever
        // loses its reporter the rule above becomes vacuous for every other file.
        val base = files.single { it.name == "MviViewModel" }.codeOnly()
        assertTrue(
            base.contains("crashReporter"),
            "MviViewModel must own the reporter — catchTo is the single funnel",
        )
    }

    @Test
    fun `the scope factory carries the background failure handler`() {
        // The guard above assumes background work reports somewhere. This is that somewhere,
        // and the only place the assumption is written down.
        val factory = files.single { it.name == "BackgroundScope" }
        assertTrue(
            factory.text.contains("BackgroundFailureHandler"),
            "The expect declaration must document — and the actuals must apply — " +
                "BackgroundFailureHandler, or a failed launch escalates to the platform's " +
                "uncaught-exception handler",
        )
    }

    @Test
    fun `the audit scans a non-empty corpus`() {
        // A guard that matched nothing would pass for the wrong reason.
        val viewModels = files.count { it.isViewModel() }
        assertTrue(viewModels > 20, "Expected a real corpus, scanned $viewModels ViewModel files")
    }
}
