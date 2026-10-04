package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Positive controls for [PassThroughUseCaseRule].
 *
 * These did not exist before 2026-10-04, which is why a rule that AGENTS.md
 * cites as "enforced by rule" had never been executed. See
 * `2026-10-04-ci-test-tag-filter-and-vacuous-gates`.
 */
class PassThroughUseCaseRuleTest {

    private val rule = PassThroughUseCaseRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    @Test
    fun `primary-constructor repository delegation is flagged`() {
        // This is the form the project actually uses. The rule used to resolve the
        // receiver via `psiClass.declarations.filterIsInstance<KtProperty>()`, but a
        // primary-constructor property is a KtParameter, not a KtProperty, so the
        // lookup returned null and the rule bailed out before reporting.
        val code = """
            package com.singularity.todo.feature.checklist

            class ArchiveItemUseCase(
                private val checklistRepository: ChecklistRepository,
            ) {
                fun archive(id: ChecklistId): Result<Unit> = checklistRepository.archive(id)
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size, "expected the pass-through to be reported")
        assertTrue(findings[0].message.contains("pass-through"))
    }

    @Test
    fun `body-declared repository delegation is flagged`() {
        // The form the rule used to handle. Kept as a regression guard: fixing
        // the constructor case must not break the property case.
        val code = """
            package com.singularity.todo.feature.checklist

            class ArchiveItemUseCase {
                private val checklistRepository: ChecklistRepository = TODO()

                fun archive(id: ChecklistId): Result<Unit> = checklistRepository.archive(id)
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
    }

    @Test
    fun `val-declared property delegation is flagged`() {
        val code = """
            package com.singularity.todo.feature.checklist

            class ArchiveItemUseCase {
                val checklistRepository: ChecklistRepository = TODO()

                fun archive(id: ChecklistId): Result<Unit> = checklistRepository.archive(id)
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
    }

    @Test
    fun `block body is not flagged`() {
        val code = """
            package com.singularity.todo.feature.checklist

            class ArchiveItemUseCase(
                private val checklistRepository: ChecklistRepository,
            ) {
                fun archive(id: ChecklistId): Result<Unit> {
                    require(id.value.isNotBlank())
                    return checklistRepository.archive(id)
                }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        assertEquals(0, rule.visitFile(ktFile, languageSettings).size)
    }

    @Test
    fun `clock delegation is not flagged`() {
        val code = """
            package com.singularity.todo.feature.checklist

            class StampUseCase(
                private val clock: Clock,
            ) {
                fun now(): Instant = clock.now()
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        assertEquals(0, rule.visitFile(ktFile, languageSettings).size)
    }

    @Test
    fun `private method is not flagged`() {
        val code = """
            package com.singularity.todo.feature.checklist

            class ArchiveItemUseCase(
                private val checklistRepository: ChecklistRepository,
            ) {
                private fun archive(id: ChecklistId): Result<Unit> = checklistRepository.archive(id)
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        assertEquals(0, rule.visitFile(ktFile, languageSettings).size)
    }

    @Test
    fun `non-repository dependency is not flagged`() {
        val code = """
            package com.singularity.todo.feature.checklist

            class ArchiveItemUseCase(
                private val encoder: ChecklistEncoder,
            ) {
                fun encode(id: ChecklistId): String = encoder.encode(id)
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        assertEquals(0, rule.visitFile(ktFile, languageSettings).size)
    }
}
