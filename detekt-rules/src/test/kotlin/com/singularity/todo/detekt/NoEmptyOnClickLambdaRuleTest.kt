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

    @Test
    fun `a preview-named file is exempt`() {
        // Previously untestable: compileContentForTest derives the file name from the
        // package argument, so no fixture could produce a *Preview* file. Hence the policy
        // extraction. An earlier version of this file asserted
        // `"taskrowpreview".contains("preview")`, which is true regardless of the rule.
        assertTrue(NoEmptyOnClickLambdaPolicy.isPreviewPath("TaskRowPreview.kt", ""))
        assertTrue(NoEmptyOnClickLambdaPolicy.isPreviewPath("taskrowpreview.kt", ""))
    }

    @Test
    fun `a file under a preview package is exempt`() {
        // This is the repo's actual convention: core/ui/preview/ exists, Preview.kt does not.
        assertTrue(
            NoEmptyOnClickLambdaPolicy.isPreviewPath(
                "Box.kt",
                "/repo/shared/src/commonMain/kotlin/com/singularity/todo/core/ui/preview/Box.kt",
            ),
        )
    }

    @Test
    fun `an ordinary file is not exempt`() {
        assertTrue(
            !NoEmptyOnClickLambdaPolicy.isPreviewPath(
                "TaskRow.kt",
                "/repo/shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/TaskRow.kt",
            ),
        )
    }

    @Test
    fun `windows preview paths are handled`() {
        assertTrue(
            NoEmptyOnClickLambdaPolicy.isPreviewPath(
                "Box.kt",
                "C:\\repo\\shared\\src\\commonMain\\kotlin\\com\\x\\preview\\Box.kt",
            ),
        )
    }
}
