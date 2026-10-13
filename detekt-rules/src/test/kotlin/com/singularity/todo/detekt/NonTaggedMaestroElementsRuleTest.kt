package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [NonTaggedMaestroElementsRule].
 */
class NonTaggedMaestroElementsRuleTest {

    private val rule = NonTaggedMaestroElementsRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    private fun findingsIn(code: String) =
        rule.visitFile(
            compileContentForTest(code.trimIndent(), "com.example"),
            languageSettings,
        )

    // ── Pattern 1: testTag = "" ───────────────────────────────────────────────

    @Test
    fun `testTag with empty string literal is flagged`() {
        val code = """
            package com.singularity.todo.feature.checklist

            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier

            @Composable
            fun ChecklistAddInput(modifier: Modifier = Modifier) {
                androidx.compose.foundation.layout.OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    modifier = modifier.testTag(""),
                    placeholder = { },
                )
            }
        """
        val findings = findingsIn(code)
        assertTrue(
            findings.any { it.message.contains("testTag = \"\"") },
            "expected finding about empty testTag, got: $findings",
        )
    }

    @Test
    fun `testTag with non-empty string is not flagged`() {
        val code = """
            package com.singularity.todo.feature.checklist

            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier

            @Composable
            fun ChecklistAddInput(modifier: Modifier = Modifier) {
                androidx.compose.foundation.layout.OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    modifier = modifier.testTag("checklist_add_input"),
                )
            }
        """
        val findings = findingsIn(code)
        assertEquals(0, findings.size, "expected no findings, got: $findings")
    }

    @Test
    fun `testTag with TestTags constant is not flagged`() {
        val code = """
            package com.singularity.todo.feature.checklist

            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier

            @Composable
            fun ChecklistAddInput(modifier: Modifier = Modifier) {
                androidx.compose.foundation.layout.OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    modifier = modifier.testTag(TestTags.CHECKLIST_ADD_INPUT),
                )
            }
        """
        val findings = findingsIn(code)
        assertEquals(0, findings.size, "expected no findings, got: $findings")
    }

    // ── Pattern 2: interactive composable with no testTag ─────────────────────

    @Test
    fun `interactive composable without testTag is flagged`() {
        val code = """
            package com.singularity.todo.feature.checklist

            import androidx.compose.runtime.Composable

            @Composable
            fun ConfirmButton(onConfirm: () -> Unit) {
                androidx.compose.material3.Button(onClick = onConfirm) { }
            }
        """
        val findings = findingsIn(code)
        assertTrue(
            findings.any {
                it.message.contains("onConfirm") && it.message.contains("no testTag")
            },
            "expected finding about missing testTag, got: $findings",
        )
    }

    @Test
    fun `interactive composable with testTag is not flagged`() {
        val code = """
            package com.singularity.todo.feature.checklist

            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier

            @Composable
            fun ConfirmButton(modifier: Modifier = Modifier, onConfirm: () -> Unit) {
                androidx.compose.material3.Button(
                    onClick = onConfirm,
                    modifier = modifier.testTag(TestTags.SHEET_CONFIRM),
                ) { }
            }
        """
        val findings = findingsIn(code)
        assertEquals(0, findings.size, "expected no findings, got: $findings")
    }

    @Test
    fun `composable with only display text is not flagged`() {
        // A label has no event handler, so pattern 2 does not apply.
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Composable

            @Composable
            fun PriorityLabel(text: String) {
                androidx.compose.material3.Text(text)
            }
        """
        val findings = findingsIn(code)
        assertEquals(0, findings.size, "expected no findings, got: $findings")
    }

    // ── Exemptions ─────────────────────────────────────────────────────────────

    @Test
    fun `preview file is exempt`() {
        val code = """
            package com.example.preview

            import androidx.compose.runtime.Composable

            @Composable
            fun TaskRowPreview() {
                ConfirmButton(onConfirm = {})
            }
        """
        val findings = findingsIn(code)
        assertEquals(0, findings.size, "preview files should be exempt, got: $findings")
    }

    @Test
    fun `preview function is exempt`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Composable

            @Composable
            fun TaskRow() {
                // ...
            }

            @androidx.compose.ui.tooling.preview.Preview
            @Composable
            fun TaskRowPreview() {
                TaskRow()
            }
        """
        val findings = findingsIn(code)
        assertEquals(
            0,
            findings.size,
            "preview functions should be exempt, got: $findings",
        )
    }

    @Test
    fun `test source is exempt`() {
        // Files under jvmTest/ are exempt — tested via policy functions below.
        assertTrue(
            NonTaggedMaestroElementsPolicy.isTestPath(
                "/repo/shared/src/jvmTest/kotlin/TaskRowTest.kt",
            ),
        )
        assertTrue(
            NonTaggedMaestroElementsPolicy.isPreviewPath(
                "TaskRowPreview.kt",
                "/repo/feature/tasks/TaskRowPreview.kt",
            ),
        )
    }

    // ── Policy functions ───────────────────────────────────────────────────────

    @Test
    fun `isHandlerParameter matches on-A-Z shape`() {
        val handlers = listOf(
            "onClick",
            "onConfirm",
            "onDismiss",
            "onDelete",
            "onCheckedChange",
            "onRetry",
            "onSave",
            "onBack",
            "onToggle",
            "onEdit",
        )
        val nonHandlers = listOf(
            "onValueChange",
            "onDispose",
            "onLayout",
            "onContextMenu",
            "onSuccess",
            "onFailure",
            "once",
            "only",
            "onward",
        )
        handlers.forEach {
            assertTrue(
                NonTaggedMaestroElementsPolicy.isHandlerParameter(it),
                "\"$it\" should be recognised as a handler",
            )
        }
        nonHandlers.forEach {
            assertTrue(
                !NonTaggedMaestroElementsPolicy.isHandlerParameter(it),
                "\"$it\" should NOT be recognised as a handler",
            )
        }
    }

    @Test
    fun `isPreviewPath detects preview files`() {
        assertTrue(
            NonTaggedMaestroElementsPolicy.isPreviewPath(
                "TaskRowPreview.kt",
                "/repo/feature/tasks/TaskRowPreview.kt",
            ),
        )
        assertTrue(
            NonTaggedMaestroElementsPolicy.isPreviewPath(
                "task_row_preview.kt",
                "/repo/feature/tasks/task_row_preview.kt",
            ),
        )
        assertTrue(
            NonTaggedMaestroElementsPolicy.isPreviewPath(
                "TasksScreen.kt",
                "/repo/feature/tasks/preview/TasksScreenPreview.kt",
            ),
        )
        assertTrue(
            !NonTaggedMaestroElementsPolicy.isPreviewPath(
                "TasksScreen.kt",
                "/repo/feature/tasks/TasksScreen.kt",
            ),
        )
    }

    @Test
    fun `isTestPath detects test source sets`() {
        assertTrue(
            NonTaggedMaestroElementsPolicy.isTestPath(
                "/repo/shared/src/commonTest/kotlin/TasksTest.kt",
            ),
        )
        assertTrue(
            NonTaggedMaestroElementsPolicy.isTestPath(
                "/repo/shared/src/jvmTest/kotlin/TasksTest.kt",
            ),
        )
        assertTrue(
            NonTaggedMaestroElementsPolicy.isTestPath(
                "/repo/shared/src/androidTest/kotlin/TasksTest.kt",
            ),
        )
        assertTrue(
            !NonTaggedMaestroElementsPolicy.isTestPath(
                "/repo/shared/src/main/kotlin/Tasks.kt",
            ),
        )
    }
}
