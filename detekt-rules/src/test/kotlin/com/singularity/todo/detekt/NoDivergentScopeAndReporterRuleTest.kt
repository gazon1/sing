package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Positive tests for [NoDivergentScopeAndReporterRule].
 *
 * The tests are pairs — the violation and the legal shape that differs by one thing — because a
 * test of a rule that only ever sees violations proves nothing about the legal shapes it has to
 * leave alone. There are three of those here, and each one was a real shape in the tree:
 *
 * - a class that declares **no** `crashReporter` at all (the coordinator's shape before the
 *   reporter migration, and still the shape of every component that reports through its scope);
 * - a class whose `scope` default already references the reporter;
 * - a binding for a component that holds no reporter and legitimately takes a scope from the
 *   graph — `SyncRunner` and `SyncRepositoryImpl`, which is why the binding finding is
 *   conditional on `crashReporter` being passed.
 *
 * ## The last test is the one that matters most
 *
 * `a class with an unrelated property named scope is not a parameter` and
 * `a secondary constructor is not the primary one` are both about matching less than intended.
 * A rule that reports every occurrence of a name is indistinguishable from a rule that reports
 * the right ones — see `2026-10-05-no-direct-dispatchers-rule-was-a-no-op`, where a rule that
 * could not fire for any input shipped registered, referenced by an ADR, and green.
 */
class NoDivergentScopeAndReporterRuleTest {

    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    private fun findingsFor(classBody: String, bindingBody: String = ""): List<dev.detekt.api.Finding> {
        val rule = NoDivergentScopeAndReporterRule(TestConfig())
        val code = """
            package com.example

            import com.singularity.todo.core.observability.CrashReportingPort
            import com.singularity.todo.core.observability.reportingScope
            import org.koin.core.module.dsl.viewModel
            import org.koin.dsl.module

            $classBody

            fun coreModule() = module {
                $bindingBody
            }
        """.trimIndent()
        return rule.visitFile(compileContentForTest(code, "com.example"), languageSettings)
    }

    // ─── Constructor finding ───────────────────────────────────────────────────────

    @Test
    fun `a scope parameter with no default is reported`() {
        val findings = findingsFor(
            """
            class SomeViewModel(
                private val repo: Repo,
                crashReporter: CrashReportingPort,
                scope: AutoCloseableCoroutineScope,
            )
            """.trimIndent(),
        )
        assertEquals(1, findings.size, "a required scope beside a reporter forces a binding to choose it")
    }

    @Test
    fun `the constructor message names the fix`() {
        // Asserted on the policy rather than through the harness: a detekt Finding's message is
        // not readable from the test API, and a message that stops naming the fix is how a rule
        // starts getting `@Suppress`ed instead of fixed.
        val message = NoDivergentScopeAndReporterPolicy.constructorMessage("SomeViewModel")
        assertTrue(
            message.contains("reportingScope(crashReporter)"),
            "the message must name the fix, got: $message",
        )
    }

    @Test
    fun `the binding message explains what the two arguments cost`() {
        val message = NoDivergentScopeAndReporterPolicy.bindingMessage("SomeViewModel")
        assertTrue(message.contains("Drop the `scope` argument"), "got: $message")
    }

    @Test
    fun `a scope default derived from the reporter is accepted`() {
        val findings = findingsFor(
            """
            class SomeViewModel(
                private val repo: Repo,
                crashReporter: CrashReportingPort,
                scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
            )
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty(), "one destination by construction — nothing to report, got $findings")
    }

    @Test
    fun `a scope with no reporter beside it is not reported`() {
        // The legitimate shape: a component that reports through its scope's handler and holds
        // no reporter of its own. Demanding a reporter here would be manufacturing a dependency
        // to satisfy a check, which the background-handler ADR explicitly rejects.
        val findings = findingsFor(
            """
            class SomeViewModel(
                private val repo: Repo,
                scope: AutoCloseableCoroutineScope,
            )
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty(), "no reporter means nothing to diverge from, got $findings")
    }

    @Test
    fun `a class with no scope parameter at all is not reported`() {
        val findings = findingsFor(
            """
            class SomeViewModel(
                private val repo: Repo,
                crashReporter: CrashReportingPort,
            )
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty(), "got $findings")
    }

    @Test
    fun `a class with an unrelated property named scope is not a parameter`() {
        // Matches less than intended if it fires. The rule reads the *primary constructor's
        // parameters*, not any member called `scope`.
        val findings = findingsFor(
            """
            class SomeViewModel(
                private val repo: Repo,
                crashReporter: CrashReportingPort,
            ) {
                private val scope = computeScope()
            }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty(), "a body property is not a constructor parameter, got $findings")
    }

    @Test
    fun `a derived secondary constructor does not excuse a required primary scope`() {
        // This is `SearchViewModel` on `main`, found by this rule rather than by reading.
        //
        // The shape looks safe: Koin resolves the *secondary* constructor, which derives the
        // scope from the reporter it was handed, so the binding cannot diverge. But the primary
        // still requires a scope, so any caller reaching it directly chooses one independently
        // of the reporter — and the class advertises a shape that permits the divergence.
        //
        // A reviewer reading only the binding would have called this consistent. I did, and
        // wrote it into issue #143 as "consistent by construction". The rule disagreed, which is
        // the whole argument for having the rule.
        val findings = findingsFor(
            """
            class SomeViewModel(
                private val repo: Repo,
                crashReporter: CrashReportingPort,
                private val scope: AutoCloseableCoroutineScope,
            ) {
                constructor(repo: Repo, crashReporter: CrashReportingPort) :
                    this(repo, crashReporter, reportingScope(crashReporter))
            }
            """.trimIndent(),
        )
        assertEquals(
            1,
            findings.size,
            "the derivation belongs on the primary, so every constructor inherits it",
        )
    }

    @Test
    fun `a primary with a derived default is accepted even with a secondary`() {
        val findings = findingsFor(
            """
            class SomeViewModel(
                private val repo: Repo,
                crashReporter: CrashReportingPort,
                scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
            ) {
                constructor(repo: Repo) : this(repo, crashReporter, reportingScope(crashReporter))
            }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty(), "got $findings")
    }

    @Test
    fun `NotePreview and NoteEditor are covered by role not by name`() {
        val findings = findingsFor(
            """
            class NoteEditor(
                private val repo: Repo,
                crashReporter: CrashReportingPort,
                scope: AutoCloseableCoroutineScope,
            )
            """.trimIndent(),
        )
        assertEquals(1, findings.size, "a name-based rule would exempt exactly these two")
    }

    // ─── Binding finding ───────────────────────────────────────────────────────────

    @Test
    fun `a binding passing both crashReporter and scope is reported`() {
        val findings = findingsFor(
            """
            class SomeViewModel(
                private val repo: Repo,
                crashReporter: CrashReportingPort,
                scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
            )
            """.trimIndent(),
            bindingBody = """
                viewModel { SomeViewModel(repo = get(), crashReporter = get(), scope = get()) }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, "a correct default overridden by the binding is still a divergence")
    }

    @Test
    fun `the real defect — a derived default overridden by a graph-supplied scope`() {
        // This is `SettingsViewModel` on `main` before the fix. The class is correct; the
        // binding replaces its derivation with a scope from the graph. A constructor-only check
        // passes it forever, which is why the binding is a separate finding.
        val findings = findingsFor(
            """
            class SettingsViewModel(
                private val repo: Repo,
                crashReporter: CrashReportingPort,
                scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
            )
            """.trimIndent(),
            bindingBody = """
                viewModel { SettingsViewModel(repo = get(), scope = get(), crashReporter = get()) }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, "expected the binding finding, got $findings")
    }

    @Test
    fun `a binding passing only the reporter is accepted`() {
        val findings = findingsFor(
            """
            class SomeViewModel(
                private val repo: Repo,
                crashReporter: CrashReportingPort,
                scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
            )
            """.trimIndent(),
            bindingBody = """
                viewModel { SomeViewModel(repo = get(), crashReporter = get()) }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty(), "the shape the fix produces, got $findings")
    }

    @Test
    fun `a binding passing a scope for a component with no reporter is accepted`() {
        // `SyncRunner` and `SyncRepositoryImpl`, both live. The binding finding is conditional
        // on the reporter being passed, so a scope here is the only way to get a scope and is
        // not a divergence.
        val findings = findingsFor(
            """
            class SomeRunner(
                private val repo: Repo,
                scope: AutoCloseableCoroutineScope,
            )
            """.trimIndent(),
            bindingBody = """
                single { SomeRunner(repo = get(), scope = get()) }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty(), "got $findings")
    }

    @Test
    fun `a non-viewModel binding is not this rule's business`() {
        val findings = findingsFor(
            """
            class SomeViewModel(
                private val repo: Repo,
                crashReporter: CrashReportingPort,
                scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
            )
            """.trimIndent(),
            bindingBody = """
                single { SomeViewModel(repo = get(), crashReporter = get(), scope = get()) }
            """.trimIndent(),
        )
        assertTrue(findings.isEmpty(), "a factory-shaped binding is NoFactoryViewModel's finding, got $findings")
    }
}
