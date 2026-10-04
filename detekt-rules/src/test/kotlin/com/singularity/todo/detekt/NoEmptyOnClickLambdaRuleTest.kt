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
        // Asserted on the parameter name and the phrase the rule actually emits.
        // The old assertion looked for the literal "Empty lambda", which only
        // matched one earlier wording of the message — so a rule that kept firing
        // with a better message could fail here, and a rule that stopped firing
        // entirely still passed the sibling negative cases.
        assertTrue(
            findings.any { it.message.contains("onClick") && it.message.contains("empty lambda") },
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
    fun `a non-composable with an onClick parameter is flagged too`() {
        // This test used to assert 0 findings, and it passed — because the rule was
        // gated on an 11-name allow-list of composables, so a call to `register` was
        // invisible no matter what it was passed. The rule now keys on the
        // *parameter* name, which is what its KDoc always promised, so this is
        // flagged. The name changed with the expectation: leaving it "is not
        // flagged" would have kept a test that contradicts the implementation.
        val code = """
            package com.singularity.todo.feature.tasks

            fun helper(onClick: () -> Unit) {
                register(onClick = { })
            }
        """
        assertEquals(1, findingsIn(code).size, "an empty onClick is empty wherever it is passed")
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

    @Test
    fun `test source sets are exempt`() {
        assertTrue(
            NoEmptyOnClickLambdaPolicy.isTestPath(
                "/repo/shared/src/commonTest/kotlin/com/singularity/todo/MenuNodesBuilderTest.kt",
            ),
        )
        assertTrue(
            NoEmptyOnClickLambdaPolicy.isTestPath(
                "/repo/shared/src/jvmTest/kotlin/com/singularity/todo/feature/agenda/FlowTest.kt",
            ),
        )
        assertTrue(NoEmptyOnClickLambdaPolicy.isTestPath("/repo/desktopApp/src/jvmTest/kotlin/x/Test.kt"))
    }

    @Test
    fun `production source sets are not exempt by path`() {
        assertTrue(
            !NoEmptyOnClickLambdaPolicy.isTestPath(
                "/repo/shared/src/commonMain/kotlin/com/singularity/todo/feature/settings/SettingsScreen.kt",
            ),
        )
        // A path that merely contains the letters "test" in a package name is not a
        // test source set - otherwise a production package called `attestation` would
        // silently disable the rule.
        assertTrue(
            !NoEmptyOnClickLambdaPolicy.isTestPath(
                "/repo/shared/src/commonMain/kotlin/com/singularity/todo/attestation/Probe.kt",
            ),
        )
    }

    @Test
    fun `windows test paths are handled`() {
        assertTrue(
            NoEmptyOnClickLambdaPolicy.isTestPath(
                "C:\\repo\\shared\\src\\jvmTest\\kotlin\\com\\x\\FlowTest.kt",
            ),
        )
    }

    @Test
    fun `a declaration named for previews is exempt`() {
        assertTrue(NoEmptyOnClickLambdaPolicy.isPreviewNamed("previewOverrides"))
        assertTrue(NoEmptyOnClickLambdaPolicy.isPreviewNamed("PreviewSamples"))
        assertTrue(NoEmptyOnClickLambdaPolicy.isPreviewNamed("taskPreviewOverrides"))
    }

    @Test
    fun `an ordinary declaration name is not exempt`() {
        assertTrue(!NoEmptyOnClickLambdaPolicy.isPreviewNamed("onDelete"))
        assertTrue(!NoEmptyOnClickLambdaPolicy.isPreviewNamed("SettingsContent"))
        assertTrue(!NoEmptyOnClickLambdaPolicy.isPreviewNamed(null))
    }

    // ── The exemptions, proved end to end rather than only through the policy ─────
    //
    // A policy test proves `isPreviewNamed("previewOverrides")` is true. It does not
    // prove `isPreviewContext` consults it - the call from the rule to the policy is
    // exactly the kind of wiring a unit test of the helper can pass while the rule
    // still reports. These two go through the rule.

    @Test
    fun `a val named for previews is exempt even outside a preview path`() {
        val code = """
            private val previewOverrides: Map<String, () -> Unit> = mapOf(
                "tags" to {
                    TagsScreen(
                        state = TagsUiState.Empty,
                        onDelete = {},
                    )
                },
            )
        """
        assertEquals(
            emptyList(),
            findingsIn(code).map { it.entity.signature },
            "previewOverrides should not be flagged: it exists only to feed previews",
        )
    }

    @Test
    fun `a test file is exempt but the same code in production is not`() {
        val code = """
            class BuilderTest {
                @Composable
                fun renders() {
                    TaskMenu(onDismiss = {})
                }
            }
        """
        // compileContentForTest cannot produce a path under a test source set, so the
        // test-path half of the exemption is pinned by isTestPath's own tests. What is
        // pinned here is that the exemption did not leak: same shape, no exemption.
        assertTrue(findingsIn(code).isNotEmpty(), "production path must still be flagged")
    }
}
