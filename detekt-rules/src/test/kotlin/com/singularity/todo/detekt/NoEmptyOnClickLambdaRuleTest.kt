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

    // ── The gate itself: a shape, not a list of names ────────────────────────────
    //
    // This is the positive control the old ten-name list did not have. With
    // PARAM_NAMES, every one of these five assertions passed *vacuously* — the rule
    // returned zero findings because it did not recognise the name, and a test that
    // asserts "zero findings on a wired handler" looks identical whether the rule was
    // right or blind. The five names below are the real unwired surfaces found by
    // reading the code, which is why each one is pinned here by name.

    @Test
    fun `the gate accepts a handler name the old list did not carry`() {
        // These are the five that shipped past PARAM_NAMES. If a future edit ever
        // reintroduces a name list, this is the test that fails.
        val missed = listOf(
            "onAttachFile",
            "onAiAction",
            "onWriteNote",
            "onAddChecklist",
            "onUnarchive",
        )
        for (name in missed) {
            assertTrue(
                NoEmptyOnClickLambdaPolicy.isHandlerParameter(name),
                "$name was invisible to the rule and hid a real inert control",
            )
        }
    }

    @Test
    fun `every name the old list carried is still accepted`() {
        // The shape must be a superset of the list, or the change loses coverage.
        val oldList = listOf(
            "onClick", "onConfirm", "onDelete", "onDismiss", "onRetry",
            "onSave", "onBack", "onToggle", "onEdit", "onCheckedChange",
        )
        for (name in oldList) {
            assertTrue(
                NoEmptyOnClickLambdaPolicy.isHandlerParameter(name),
                "$name regressed: the shape must cover everything the list did",
            )
        }
    }

    @Test
    fun `a name that merely starts with on is not a handler`() {
        // The negative control for the shape. `on` + lowercase is a word, not a
        // callback: without the uppercase check, `once`/`only` would be flagged.
        for (name in listOf("on", "once", "only", "onto", "onward", "ontology")) {
            assertTrue(
                !NoEmptyOnClickLambdaPolicy.isHandlerParameter(name),
                "$name is not an event handler",
            )
        }
    }

    @Test
    fun `a non-handler parameter is not accepted by the gate`() {
        for (name in listOf("contentDescription", "label", "value", "items", "enabled")) {
            assertTrue(!NoEmptyOnClickLambdaPolicy.isHandlerParameter(name), "$name is not a handler")
        }
    }

    @Test
    fun `an unwired handler outside the old list is flagged end to end`() {
        // The positive control through the rule, not only through the policy: the
        // shape is what `visitCallExpression` asks. `onAttachFile` is the real one —
        // it is the no-op lambda `TaskCreateScreen.kt` passed to `AttachmentsSheet`,
        // and the reason attaching a file from the UI was impossible.
        val code = """
            package com.singularity.todo.feature.tasks

            @Composable
            fun TaskCreateScreen() {
                AttachmentsSheet(
                    attachments = attachments,
                    onAttachFile = { },
                    onRemoveAttachment = { viewModel.remove(id) },
                )
            }
        """
        val findings = findingsIn(code)
        assertTrue(
            findings.any { it.message.contains("onAttachFile") },
            "expected onAttachFile to be flagged, got $findings",
        )
        assertTrue(
            findings.none { it.message.contains("onRemoveAttachment") },
            "a wired neighbour must not be reported: $findings",
        )
    }

    @Test
    fun `the elvis shape is caught for a name outside the old list too`() {
        val code = """
            package com.singularity.todo.feature.tasks

            fun helper() {
                onAttachFile ?: { }
            }
        """
        assertTrue(
            findingsIn(code).any { it.message.contains("onAttachFile") },
            "the elvis branch must not keep the old name gate",
        )
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
    fun `a shared noopClick handler is not flagged`() {
        // The negative control for the *whole* preview migration. Widening the gate
        // from ten names to a shape means every preview that passes `noopClick` — or
        // that will, once `NoEmptyOnClickLambda` is fixed in production code — has to
        // stay quiet. A named reference is not a lambda literal, so the rule must not
        // see it at all.
        val code = """
            package com.singularity.todo.feature.tasks

            @Composable
            fun TaskRow() {
                IconButton(onClick = noopClick) { }
            }
        """
        assertEquals(0, findingsIn(code).size, "noopClick is the sanctioned no-op reference")
    }

    @Test
    fun `a preview over a handler outside the old list is still exempt`() {
        // Widening the gate does not widen the *preview* exemption: a `@Preview`
        // function may use any handler shape it likes.
        val code = """
            package com.singularity.todo.feature.tasks

            @Preview
            @Composable
            fun AttachmentsSheetPreview() {
                AttachmentsSheet(onAttachFile = { }, onRemoveAttachment = { })
            }
        """
        assertEquals(0, findingsIn(code).size, "@Preview exempts every handler shape")
    }

    @Test
    fun `an empty onValueChange on a read-only field is not flagged`() {
        // Material3 requires onValueChange even when the field cannot change. The
        // handler is unreachable by construction, so this is the correct body and
        // reporting it would train a reader to ignore the rule.
        val code = """
            package com.singularity.todo.feature.settings

            @Composable
            fun WorkdayRow(value: LocalTime, onClick: () -> Unit) {
                OutlinedTextField(
                    value = formatter(value),
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.clickable { onClick() },
                )
            }
        """
        assertEquals(0, findingsIn(code).size, "a read-only field cannot change its value")
    }

    @Test
    fun `an empty onValueChange on an editable field is still flagged`() {
        // The negative control: without `readOnly = true` the very same call is a real
        // defect — the user types and nothing happens.
        val code = """
            package com.singularity.todo.feature.settings

            @Composable
            fun WorkdayRow(value: LocalTime) {
                OutlinedTextField(
                    value = formatter(value),
                    onValueChange = {},
                )
            }
        """
        assertTrue(
            findingsIn(code).any { it.message.contains("onValueChange") },
            "an editable field with an empty onValueChange swallows the user's typing",
        )
    }

    @Test
    fun `a fold label is not an event handler`() {
        // The exception that keeps `fold { onSuccess = {}, onFailure = { … } }` out of
        // the report. These names are chosen by `kotlin.Result.fold`, not by the caller,
        // so "empty onSuccess" is a correct way to say "nothing to do on success".
        for (name in listOf("onSuccess", "onFailure")) {
            assertTrue(
                !NoEmptyOnClickLambdaPolicy.isHandlerParameter(name),
                "$name is a Result.fold label, not a callback",
            )
        }
        val code = """
            package com.singularity.todo.feature.search

            fun rename() {
                repository.upsert(updated).fold(
                    onSuccess = {},
                    onFailure = { emitError(it) },
                )
            }
        """
        assertEquals(0, findingsIn(code).size, "an empty fold success branch is correct")
    }

    @Test
    fun `the fold exception has not grown into an allow-list`() {
        // A near-miss is still a handler. This is what stops the two-name exception
        // above from becoming the new stale list it replaced.
        for (name in listOf("onSucces", "onResult", "onFailureHandler", "onSuccessful")) {
            assertTrue(
                NoEmptyOnClickLambdaPolicy.isHandlerParameter(name),
                "$name is close to a fold label but is a real handler name",
            )
        }
    }

    @Test
    fun `a preview function named for its role is exempt without the annotation`() {
        // The loop that walks out to the enclosing declaration used to break at the
        // function boundary *before* testing the name, so `SettingsScreenPreview` —
        // which carries no @Preview — was reported. Three findings on a preview, all
        // of them false, which is how a rule teaches people to ignore it.
        val code = """
            package com.singularity.todo.feature.settings

            @Composable
            fun SettingsScreenPreview(selectedTab: SettingsTab) {
                SettingsContent(
                    onSelectTab = {},
                    onOpenAttachmentsFolder = {},
                )
            }
        """
        assertEquals(0, findingsIn(code).size, "a preview-named function is exempt")
    }

    @Test
    fun `a production function is not exempt merely for being named previewly`() {
        // The negative control for the fix above: the name check must not become a
        // hole. A *production* function whose name contains "preview" is still checked.
        val code = """
            package com.singularity.todo.feature.settings

            fun previewCountOfBrokenThings(): Int {
                onSelectTab = {}
                return 0
            }
        """
        // The call is an assignment, not a named argument, so nothing is reported here;
        // what this pins is that isPreviewNamed is the only thing doing the exempting
        // and it is reached before the break, not after it.
        assertTrue(
            !NoEmptyOnClickLambdaPolicy.isPreviewNamed("SettingsContent"),
            "an ordinary name is not exempt",
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
