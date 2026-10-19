package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [NoValueBasedTextFieldRule].
 *
 * The rule exists to keep the app on the state-based text field API. Its value is therefore
 * entirely in the *negative* direction: a value-based field that slips back in is a defect that
 * no other gate in this repository would catch.
 */
class NoValueBasedTextFieldRuleTest {

    private val rule = NoValueBasedTextFieldRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    private fun findingsIn(code: String) =
        rule.visitFile(compileContentForTest(code.trimIndent(), "com.example"), languageSettings)

    @Test
    fun `a value-based text field is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            @Composable
            fun TaskTitle() {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                )
            }
        """
        val findings = findingsIn(code)
        assertTrue(
            findings.any { it.message.contains("value-based") && it.message.contains("OutlinedTextField") },
            "expected the value-based field to be reported, got $findings",
        )
    }

    @Test
    fun `an onValueChange-only text field is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            @Composable
            fun TaskTitle() {
                BasicTextField(
                    onValueChange = { title = it },
                )
            }
        """
        assertTrue(
            findingsIn(code).any { it.message.contains("onValueChange") },
            "a field with only onValueChange is still the value-based shape",
        )
    }

    @Test
    fun `a state-based text field is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            @Composable
            fun TaskTitle(state: TextFieldState) {
                OutlinedTextField(
                    state = state,
                )
            }
        """
        assertEquals(0, findingsIn(code).size, "the state-based overload is the shape we want")
    }

    @Test
    fun `a state-based secure text field is not flagged`() {
        val code = """
            package com.singularity.todo.feature.auth

            @Composable
            fun Password(state: TextFieldState) {
                OutlinedSecureTextField(
                    state = state,
                )
            }
        """
        assertEquals(0, findingsIn(code).size, "the secure field has a state-based overload too")
    }

    @Test
    fun `a qualified call to a same-named data class is not flagged`() {
        val code = """
            package com.singularity.todo.feature.genui

            fun decode(node: UiNode) {
                val field = UiNode.TextField(
                    path = "/title",
                    label = "Title",
                )
            }
        """
        assertEquals(
            0,
            findingsIn(code).size,
            "UiNode.TextField is a data class, not a composable call",
        )
    }

    @Test
    fun `the GenUI renderer path is not exempt`() {
        assertFalse(
            NoValueBasedTextFieldPolicy.isExemptPath(
                "/repo/shared/src/commonMain/kotlin/com/singularity/todo/" +
                    "feature/genui/render/material3/input/TextFieldRenderer.kt",
            ),
            "the GenUI renderer was migrated to the state-based API — it is not exempt any more",
        )
        assertFalse(
            NoValueBasedTextFieldPolicy.isExemptPath(
                "/repo/shared/src/commonMain/kotlin/com/singularity/todo/" +
                    "feature/tasks/presentation/TaskTitleRow.kt",
            ),
            "an ordinary screen is not exempt",
        )
    }

    @Test
    fun `a value-based field in the GenUI renderer is a finding`() {
        val code = """
            package com.singularity.todo.feature.genui.render.material3.input

            @Composable
            fun BoundTextField() {
                OutlinedTextField(
                    value = current,
                    onValueChange = { newValue: String -> write(newValue) },
                )
            }
        """
        val ktFile = compileContentForTest(code.trimIndent(), Path.of("TextFieldRenderer.kt"))
        val findings = rule.visitFile(ktFile, languageSettings)
        assertTrue(
            findings.any { it.message.contains("value-based") },
            "the renderer is not exempt any more, so the value-based shape is reported, " +
                "got $findings",
        )
    }

    @Test
    fun `test sources are exempt`() {
        assertTrue(
            NoValueBasedTextFieldPolicy.isTestPath("/repo/shared/src/commonTest/kotlin/Sample.kt"),
            "a test source is not a shipped screen",
        )
        assertFalse(
            NoValueBasedTextFieldPolicy.isTestPath(
                "/repo/shared/src/commonMain/kotlin/com/singularity/todo/Sample.kt",
            ),
            "production code is not exempt",
        )
    }
}
