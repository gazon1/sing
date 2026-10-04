package com.singularity.todo.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A ViewModel that owns `emitError`/`catchTo` call sites but never passes a
 * `CrashReportingPort` inherits `MviViewModel`'s no-op default, so its failures reach the
 * UI and nothing else. The code compiles, the ViewModel is fully wired, and the reporting
 * silently does nothing — the "implemented but unwired" failure mode this project audits
 * for, arriving through a defaulted parameter instead of a missing `single {}`.
 *
 * This is a structural check rather than a behavioural one: "reported nothing" is the
 * absence of an effect and cannot be observed from outside. So the invariant is asserted
 * on source shape instead.
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
    }

    @Test
    fun `every viewmodel that reports errors also wires a crash reporter`() {
        val offenders = files
            .filter { it.isViewModel() }
            .map { it to it.codeOnly() }
            .filter { (_, code) -> code.contains("emitError(") || code.contains("catchTo(") }
            .filterNot { (_, code) -> code.contains("crashReporter") }
            .map { (file, _) -> file.name }

        if (offenders.isNotEmpty()) {
            fail(
                "These files call emitError/catchTo but never pass a CrashReportingPort, so " +
                    "their failures are silently dropped:\n  " + offenders.joinToString("\n  ") +
                    "\nAdd `crashReporter: CrashReportingPort` to the constructor and `get()` " +
                    "to the Koin binding.",
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
    fun `the audit scans a non-empty corpus`() {
        // A guard that matched nothing would pass for the wrong reason.
        val viewModels = files.count { it.isViewModel() }
        assertTrue(viewModels > 20, "Expected a real corpus, scanned $viewModels ViewModel files")
    }
}
