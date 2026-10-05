package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Positive tests for [AppErrorCodeRule].
 *
 * The motivating defect is that all 29 `AppError` construction sites in `commonMain` shipped
 * with the subtype-wide default, so the crash dashboard held one `error.validation` group
 * containing fourteen unrelated failures. The tests below are pairs: the violation, and the
 * legal shape that differs by exactly one thing.
 */
class AppErrorCodeRuleTest {

    private val rule = AppErrorCodeRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    private fun findingsFor(body: String): List<dev.detekt.api.Finding> {
        val code = """
            package com.example

            import com.singularity.todo.core.error.AppError

            $body
        """.trimIndent()
        return rule.visitFile(compileContentForTest(code, "com.example"), languageSettings)
    }

    @Test
    fun `a validation without a code is flagged`() {
        val findings = findingsFor(
            """
            fun check(title: String) {
                if (title.isBlank()) throw AppError.Validation("Title cannot be blank")
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, findings.map { it.message }.toString())
        assertTrue(findings[0].message.contains("error.validation"), findings[0].message)
    }

    @Test
    fun `a validation with a code is not flagged`() {
        val findings = findingsFor(
            """
            fun check(title: String) {
                if (title.isBlank()) {
                    throw AppError.Validation("Title cannot be blank", code = "task.title.blank")
                }
            }
            """.trimIndent(),
        )
        assertEquals(0, findings.size, findings.map { it.message }.toString())
    }

    @Test
    fun `a code alongside a cause is recognised`() {
        // The named argument is not in first position, and `cause` must not be mistaken for it.
        val findings = findingsFor(
            """
            fun check() {
                throw AppError.NotFound("Task gone", cause = IllegalStateException("x"), code = "task.not_found")
            }
            """.trimIndent(),
        )
        assertEquals(0, findings.size, findings.map { it.message }.toString())
    }

    @Test
    fun `a cause without a code is still flagged`() {
        val findings = findingsFor(
            """
            fun check() {
                throw AppError.Persistence("write failed", cause = IllegalStateException("disk"))
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, "a cause is not a code")
    }

    @Test
    fun `the fully qualified spelling is flagged`() {
        val findings = findingsFor(
            """
            fun check() {
                throw com.singularity.todo.core.error.AppError.Network("timeout")
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, "a fully qualified construction is the same construction")
    }

    @Test
    fun `the imported spelling is flagged, which is the one every real call site uses`() {
        // The first draft of the rule matched only the fully-qualified receiver and therefore
        // matched nothing: a file that imports AppError writes `AppError.Validation(…)`, and the
        // import is not in the text a rule sees. This test exists so that mistake cannot come
        // back as a "passing" rule.
        val findings = findingsFor(
            """
            fun check() { throw AppError.Unauthorized("no session") }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, "the simple-name receiver must match")
    }

    @Test
    fun `an unrelated construction is not flagged`() {
        val findings = findingsFor(
            """
            fun check() { println("Title cannot be blank") }
            """.trimIndent(),
        )
        assertEquals(0, findings.size)
    }

    @Test
    fun `every AppError subtype is covered`() {
        // The subtype list is a literal in the policy. A subtype added to the sealed class and
        // not to the rule is a construction site that stops being checked — the exact shape of
        // defect this rule exists to prevent, one level up.
        val expected = setOf("Validation", "NotFound", "Unauthorized", "Persistence", "Network", "Unknown")
        assertEquals(expected, AppErrorCodePolicy.subtypes(), "subtype list drifted from AppError")

        for (subtype in expected) {
            val findings = findingsFor("fun check() { throw AppError.$subtype(\"boom\") }")
            assertEquals(1, findings.size, "$subtype without a code must be flagged")
        }
    }
}
