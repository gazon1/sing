package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Positive tests for [NoUnwiredReporterInBindingRule].
 *
 * The defect this rule exists for is concrete: on `main`, two production bindings constructed a
 * ViewModel whose class declared `crashReporter: CrashReportingPort = NoOpCrashReportingPort()`
 * and `scope = reportingScope(crashReporter)`, and passed neither. Every failure those ViewModels
 * handled went to a no-op. The class-level rule passed, and would have kept passing.
 *
 * The tests are pairs — the violation and the legal shape that differs by one thing.
 */
class NoUnwiredReporterInBindingRuleTest {

    private val rule = NoUnwiredReporterInBindingRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    private fun findingsFor(body: String): List<dev.detekt.api.Finding> {
        val code = """
            package com.example

            import com.singularity.todo.core.observability.CrashReportingPort
            import org.koin.core.module.dsl.viewModel
            import org.koin.dsl.module

            class SomeViewModel(
                private val repo: Repo,
                crashReporter: CrashReportingPort,
                scope: AutoCloseableCoroutineScope,
            )

            $body
        """.trimIndent()
        return rule.visitFile(compileContentForTest(code, "com.example"), languageSettings)
    }

    @Test
    fun `the bare imported form is the one that matters`() {
        // Pinned separately because the first draft of the rule read the receiver from
        // `parent as KtDotQualifiedExpression` and returned null for this shape: with an import
        // in scope there is no dot-qualified parent at all. Every real binding in the tree is
        // written this way, so the draft matched nothing while compiling and passing.
        val findings = findingsFor(
            """
            fun bindings() = module {
                viewModel { SomeViewModel(get(), get(), get()) }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, "the imported bare form must match")
    }

    @Test
    fun `a viewModel binding omitting crashReporter is flagged`() {
        val findings = findingsFor(
            """
            fun bindings() = module {
                viewModel { SomeViewModel(get(), get(), get()) }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, findings.map { it.message }.toString())
        assertTrue(findings[0].message.contains("SomeViewModel"), findings[0].message)
        assertTrue(findings[0].message.contains("crashReporter = get()"), findings[0].message)
    }

    @Test
    fun `a binding that passes crashReporter is not flagged`() {
        val findings = findingsFor(
            """
            fun bindings() = module {
                viewModel { SomeViewModel(get(), crashReporter = get(), scope = get()) }
            }
            """.trimIndent(),
        )
        assertEquals(0, findings.size, findings.map { it.message }.toString())
    }

    @Test
    fun `the explicit-type form is flagged too`() {
        // `viewModel<CalendarSyncViewModel> { … }` is a different spelling with the same
        // meaning; a rule that only matched the bare receiver would miss half the bindings.
        val findings = findingsFor(
            """
            fun bindings() = module {
                viewModel<SomeViewModel> { SomeViewModel(get(), get(), get()) }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, findings.map { it.message }.toString())
    }

    @Test
    fun `the fully qualified receiver is recognised`() {
        val findings = findingsFor(
            """
            fun bindings() = module {
                koin.core.module.dsl.viewModel { SomeViewModel(get(), get(), get()) }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, "the qualified receiver is the same binding")
    }

    @Test
    fun `a nested dependency constructor is not mistaken for the bound type`() {
        // The real TaskDetailCoordinator binding: `TaskDetailDeps(...)` is constructed first,
        // inside `TaskDetailCoordinator(...)`. Reading the *first* call would report on
        // `TaskDetailDeps` — a plain data holder with no reporter and no business.
        val findings = findingsFor(
            """
            fun bindings() = module {
                viewModel { (id: String) ->
                    SomeViewModel(
                        deps = SomeDeps(
                            repo = get(),
                        ),
                        crashReporter = get(),
                        scope = get(),
                    )
                }
            }
            """.trimIndent(),
        )
        assertEquals(0, findings.size, findings.map { it.message }.toString())
    }

    @Test
    fun `a single binding is not this rule's business`() {
        val findings = findingsFor(
            """
            fun bindings() = module {
                single { SomeViewModel(get(), get(), get()) }
            }
            """.trimIndent(),
        )
        assertEquals(0, findings.size, "a non-ViewModel binding is a different mistake")
    }

    @Test
    fun `a non-ViewModel-shaped type is not flagged`() {
        val findings = findingsFor(
            """
            fun bindings() = module {
                viewModel { SomeScreenModel(get(), get(), get()) }
            }
            """.trimIndent(),
        )
        assertEquals(0, findings.size, "the name list is the one NoUnreportedFailurePath uses")
    }

    @Test
    fun `NotePreview and NoteEditor are ViewModels by role and are flagged`() {
        // Both are constructed in a `viewModel { }` binding, both match the shared name list,
        // and a rule that only looked for a `ViewModel` suffix would miss them.
        for (name in listOf("NotePreview", "NoteEditor")) {
            val findings = findingsFor(
                """
                fun bindings() = module {
                    viewModel { $name(get(), get(), get()) }
                }
                """.trimIndent(),
            )
            assertEquals(1, findings.size, "$name is on the name list by hand")
        }
    }

    @Test
    fun `the message names the shape of the defect, not just the omission`() {
        val findings = findingsFor(
            """
            fun bindings() = module {
                viewModel { SomeViewModel(get(), get(), get()) }
            }
            """.trimIndent(),
        )
        // A message that only said "add crashReporter" would be satisfied by adding a parameter
        // to a class that cannot fail — the thing the ADR rejects. The suppression route has to
        // be in the message or nobody will find it.
        assertTrue(
            findings[0].message.contains("suppress"),
            "the message must offer the alternative: ${findings[0].message}",
        )
    }
}
