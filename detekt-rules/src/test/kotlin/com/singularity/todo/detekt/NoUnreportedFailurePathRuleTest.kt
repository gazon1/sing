package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Positive tests for [NoUnreportedFailurePathRule].
 *
 * Every test here states a case the rule *must* flag, plus the near-miss it must not. The
 * near-misses matter more: this repository has two decision records about rules that compiled,
 * were registered, were configured, and never fired
 * (`2026-10-05-no-direct-dispatchers-rule-was-a-no-op`, and the `no-direct-dispatchers` /
 * `user-scoped-repository` pair that stayed dormant until 2026-10-05 despite being
 * implemented, packaged and registered). A rule that reports everything is caught by the first
 * real codebase it meets; a rule that reports nothing is caught by nothing, ever.
 *
 * So each block is a pair: the violation and the legal shape that differs from it by exactly
 * one thing.
 */
class NoUnreportedFailurePathRuleTest {

    private val rule = NoUnreportedFailurePathRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    private fun findingsFor(body: String): List<dev.detekt.api.Finding> {
        val code = """
            package com.example

            import com.singularity.todo.observability.CrashReportingPort
            import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.Dispatchers
            import kotlinx.coroutines.SupervisorJob
            import kotlinx.coroutines.flow.catch
            import kotlinx.coroutines.launch

            $body
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        return rule.visitFile(ktFile, languageSettings)
    }

    // ---------------------------------------------------------------- finding 1: no reporter

    @Test
    fun `a viewmodel that launches work and holds no reporter is flagged`() {
        val findings = findingsFor(
            """
            class TasksViewModel(
                private val repo: Repo,
                private val scope: AutoCloseableCoroutineScope,
            ) {
                fun load() {
                    scope.launch { repo.observe().collect { } }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, "expected exactly one finding, got ${findings.map { it.message }}")
        assertTrue(findings[0].message.contains("TasksViewModel"))
        assertTrue(findings[0].message.contains("CrashReportingPort"))
    }

    @Test
    fun `a viewmodel that handles failure by hand and reports nothing is flagged`() {
        // This is the exact blind spot of the three regexes the rule replaces: no
        // `emitError(`, no `catchTo(` anywhere, yet every failure is converted to UI state
        // and sent nowhere. Six real ViewModels looked like this.
        val findings = findingsFor(
            """
            class ArchiveViewModel(
                private val repo: Repo,
                private val scope: AutoCloseableCoroutineScope,
            ) {
                fun load() {
                    scope.launch {
                        runCatching { repo.read() }
                            .onSuccess { }
                            .onFailure { e -> _state.value = UiState.Failed(e) }
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, "a hand-caught failure with no reporter is unreported")
    }

    @Test
    fun `a viewmodel that only catches a flow is flagged`() {
        val findings = findingsFor(
            """
            class SearchViewModel(
                private val repo: Repo,
                private val scope: AutoCloseableCoroutineScope,
            ) {
                fun start() {
                    scope.launch {
                        repo.query().catch { e -> _state.value = UiState.Failed(e) }.collect { }
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, "a flow `catch` is a failure path")
    }

    @Test
    fun `a viewmodel that only has a try-catch clause is flagged`() {
        val findings = findingsFor(
            """
            class StatsViewModel(
                private val repo: Repo,
                private val scope: AutoCloseableCoroutineScope,
            ) {
                fun compute(): Int = try {
                    repo.count()
                } catch (e: Exception) {
                    0
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, "a catch clause is a failure path")
    }

    @Test
    fun `a viewmodel with a crashReporter parameter is not flagged`() {
        val findings = findingsFor(
            """
            class TasksViewModel(
                private val repo: Repo,
                private val crashReporter: CrashReportingPort,
                private val scope: AutoCloseableCoroutineScope,
            ) {
                fun load() {
                    scope.launch { repo.observe().collect { } }
                }
            }
            """.trimIndent(),
        )
        assertEquals(0, findings.size, "a wired reporter is the legal shape")
    }

    @Test
    fun `a viewmodel that calls report directly is not flagged`() {
        // It reports without a constructor parameter — e.g. a ViewModel that holds a
        // reporter of a different name it obtained elsewhere. Naming the call is enough.
        val findings = findingsFor(
            """
            class TasksViewModel(
                private val sink: FailureSink,
                private val scope: AutoCloseableCoroutineScope,
            ) {
                fun load() {
                    scope.launch {
                        try {
                            risky()
                        } catch (e: Exception) {
                            report(e)
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(0, findings.size, "a direct report() call is a reporter")
    }

    @Test
    fun `a viewmodel that cannot fail is not flagged`() {
        // The legal shape this guards against over-firing: a ViewModel that only transforms
        // already-loaded state has no failure path, so demanding a reporter of it would be
        // noise the author has to suppress.
        val findings = findingsFor(
            """
            class UiStateViewModel(
                private val initial: String,
                private val scope: AutoCloseableCoroutineScope,
            ) {
                fun label(): String = initial.trim()
            }
            """.trimIndent(),
        )
        assertEquals(0, findings.size, "no launch, no runCatching, no catch — nothing to report")
    }

    @Test
    fun `a non-viewmodel class is not examined`() {
        val findings = findingsFor(
            """
            class TaskFormatter(private val scope: AutoCloseableCoroutineScope) {
                fun load() {
                    scope.launch { risky() }
                }
            }
            """.trimIndent(),
        )
        assertEquals(0, findings.size, "the rule is scoped to ViewModels by name")
    }

    @Test
    fun `NotePreview is examined although it is a viewmodel by role not by name`() {
        val findings = findingsFor(
            """
            class NotePreview(
                private val scope: AutoCloseableCoroutineScope,
            ) {
                fun load() {
                    scope.launch { risky() }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, "NotePreview is on the name list by hand")
    }

    @Test
    fun `a failure path inside a lambda the viewmodel passed elsewhere is still seen`() {
        // The blind spot a source-text scan of call sites cannot have: the launch is not in
        // this file's own statement list, it is inside a lambda handed to another object.
        val findings = findingsFor(
            """
            class TagGroupsViewModel(
                private val groups: GroupStore,
                private val scope: AutoCloseableCoroutineScope,
            ) {
                fun build() {
                    groups.each { name ->
                        scope.launch { load(name) }
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, "a launch inside a nested lambda is still a failure path")
    }

    // ------------------------------------------------------- finding 2: hand-built scope

    @Test
    fun `a viewmodel constructing GlobalScope is flagged as a scope bypass`() {
        val findings = findingsFor(
            """
            class TasksViewModel(
                private val repo: Repo,
                private val crashReporter: CrashReportingPort,
            ) {
                fun load() {
                    GlobalScope.launch { repo.observe().collect { } }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("GlobalScope"), findings[0].message)
    }

    @Test
    fun `a viewmodel constructing a raw CoroutineScope is flagged`() {
        val findings = findingsFor(
            """
            class TasksViewModel(
                private val repo: Repo,
                private val crashReporter: CrashReportingPort,
            ) {
                private val own = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                fun load() {
                    own.launch { repo.observe().collect { } }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("CoroutineScope"), findings[0].message)
    }

    @Test
    fun `a viewmodel taking an injected AutoCloseableCoroutineScope is not a bypass`() {
        val findings = findingsFor(
            """
            class TasksViewModel(
                private val repo: Repo,
                private val crashReporter: CrashReportingPort,
                private val scope: AutoCloseableCoroutineScope,
            ) {
                private val own = AutoCloseableCoroutineScope()
                fun load() {
                    own.launch { repo.observe().collect { } }
                }
            }
            """.trimIndent(),
        )
        assertEquals(0, findings.size, "AutoCloseableCoroutineScope comes from the managed factory")
    }

    @Test
    fun `a scope bypass is reported alone, not doubled by the missing reporter`() {
        // Two findings for one class would bury the more serious one under the lesser.
        val findings = findingsFor(
            """
            class TasksViewModel(private val repo: Repo) {
                fun load() {
                    MainScope().launch { repo.observe().collect { } }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, findings.map { it.message }.toString())
        assertTrue(findings[0].message.contains("constructs its own scope"), findings[0].message)
    }

    // --------------------------------------------------- the guard against a dormant rule

    @Test
    fun `the rule fires on every case the retired regexes missed`() {
        // Regression evidence, in code. The three text predicates this rule replaced could not
        // see any of these; if a future refactor quietly makes the rule match nothing again,
        // this is the test that fails first.
        val bodies = listOf(
            "class AViewModel(private val s: AutoCloseableCoroutineScope) { fun g() { s.launch { f() } } }",
            "class BViewModel(private val s: AutoCloseableCoroutineScope) { fun g() { runCatching { f() } } }",
            "class CViewModel(private val s: AutoCloseableCoroutineScope) { fun g() { try { f() } catch (e: Exception) { } } }",
            "class DViewModel(private val s: AutoCloseableCoroutineScope) { fun g() { s.launch { flow.catch { } } } }",
            "class EViewModel { fun g() { GlobalScope.launch { f() } } }",
            "class FViewModel { private val s = MainScope() }",
        )
        for (body in bodies) {
            val findings = findingsFor(body)
            assertEquals(1, findings.size, "expected a finding for: $body — got ${findings.map { it.message }}")
        }
    }
}
