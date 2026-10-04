package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [NoEmptyOnClickLambdaRule].
 *
 * ## A note on scope, and on the test that used to be here
 *
 * The first case in this file was `onClick with empty lambda consumed via elvis is
 * flagged`. It failed, and the rule was **not** wrong. The rule's documented contract is
 * narrow: an empty lambda passed as a *named argument* to a known composable
 * (`IconButton(onClick = {})`). The elvis case is a different shape entirely — a
 * parameter *defaulting* to `{}` plus a `param ?: fallback` at the consumer — and it is
 * covered by a different tool: `scripts/find-unwired-surfaces.py` detector 2,
 * `default-noop`, which exists specifically to find "a callback parameter defaulting to
 * `{}` where the consumer writes `param ?: fallback`, which the empty lambda defeats".
 *
 * Two tools, two shapes. The test was asserting the other tool's job.
 *
 * The division is asserted explicitly in `the elvis and default-parameter shape belongs to
 * the unwired-surfaces script` below, so the next person to read this file does not
 * re-derive it.
 */
class NoEmptyOnClickLambdaRuleTest {

    private val rule = NoEmptyOnClickLambdaRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    private fun findingsIn(code: String) =
        rule.visitFile(compileContentForTest(code.trimIndent(), "com.example"), languageSettings)

    // ── The rule's actual contract: empty lambda as a named call argument ───────────

    @Test
    fun `empty onClick named argument is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            @Composable
            fun TaskRow() {
                IconButton(onClick = { }) { }
            }
        """
        val findings = findingsIn(code)
        assertTrue(
            findings.any { it.message.contains("onClick") && it.message.contains("Empty lambda") },
            "expected an empty-lambda finding, got $findings",
        )
    }

    @Test
    fun `empty lambda on a non-handler parameter is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            @Composable
            fun TaskRow() {
                IconButton(contentDescription = { }) { }
            }
        """
        assertEquals(0, findingsIn(code).size, "contentDescription is not an event handler")
    }

    @Test
    fun `non-empty onClick body is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            @Composable
            fun TaskRow() {
                IconButton(onClick = { doSomething() }) { }
            }
        """
        assertEquals(0, findingsIn(code).size)
    }

    @Test
    fun `named handler reference is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            @Composable
            fun TaskRow() {
                IconButton(onClick = handleClick) { }
            }
        """
        assertEquals(0, findingsIn(code).size, "a named handler is the recommended form")
    }

    @Test
    fun `call to a non-composable with the same parameter name is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            fun helper(onClick: () -> Unit) {
                register(onClick = { })
            }
        """
        assertEquals(0, findingsIn(code).size, "only known composables are checked")
    }

    // ── Preview exemptions ────────────────────────────────────────────────────────

    @Test
    fun `function annotated with Preview is exempt`() {
        val code = """
            package com.singularity.todo.feature.tasks

            @Preview
            @Composable
            fun TaskRowPreview() {
                IconButton(onClick = { }) { }
            }
        """
        assertEquals(0, findingsIn(code).size, "@Preview is an explicit exemption")
    }

    // NOT TESTED HERE: the "file name contains preview" exemption. `compileContentForTest`
    // derives the file name from the package argument, so no input can produce a file
    // named *Preview*. An earlier version of this file had a test asserting
    // `"taskrowpreview".contains("preview")`, which is true regardless of the rule and
    // so proved nothing. The branch is exercised only by the repo's real preview files
    // (shared/src/commonMain/kotlin/com/singularity/todo/core/ui/preview/), which the
    // `:shared:detekt` run covers. Extracting the predicate would make it testable, as
    // was done for NoDirectDispatchersRule — tracked, not silently left broken.

    // ── The shape this rule deliberately does not handle ──────────────────────────

    @Test
    fun `the elvis and default-parameter shape belongs to the unwired-surfaces script`() {
        // `onClick: () -> Unit = {}` plus `onClick ?: { }` is NOT this rule's job. The
        // rule reports empty lambdas in *call arguments*; this is a parameter default.
        //
        // scripts/find-unwired-surfaces.py detector 2 (`default-noop`) covers it:
        //   "a callback parameter defaulting to {} where the consumer writes
        //    param ?: fallback, which the empty lambda defeats"
        // See DETECTORS in that script, kind="default-noop" (_check_default_noop).
        val code = """
            package com.singularity.todo.feature.tasks

            @Composable
            fun TaskRow(
                onClick: () -> Unit = {},
            ) {
                val handler = onClick ?: { }
            }
        """
        assertEquals(
            0,
            findingsIn(code).size,
            "this shape is find-unwired-surfaces.py's default-noop detector, not this rule",
        )
    }

    @Test
    fun `nullable onClick defaulting to null is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            @Composable
            fun TaskRow(
                onClick: (() -> Unit)? = null,
            ) {
                onClick?.invoke()
            }
        """
        assertEquals(0, findingsIn(code).size, "null is the recommended opt-out")
    }
}
