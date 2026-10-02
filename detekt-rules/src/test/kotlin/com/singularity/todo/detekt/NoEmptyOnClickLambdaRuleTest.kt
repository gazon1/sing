package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoEmptyOnClickLambdaRuleTest {

    private val rule = NoEmptyOnClickLambdaRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    @Test
    fun `onClick with empty lambda consumed via elvis is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            @Composable
            fun TaskRow(
                onClick: () -> Unit = {},
                onToggle: () -> Unit = {},
            ) {
                val handler = onClick ?: { }
                onToggle ?: { }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertTrue(findings.any { it.message.contains("onClick") && it.message.contains("empty lambda") })
    }

    @Test
    fun `onClick with empty lambda without elvis fallback is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            @Composable
            fun TaskRow(
                onClick: () -> Unit = {},
            ) {
                onClick()
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(0, findings.size)
    }

    @Test
    fun `nullable onClick is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            @Composable
            fun TaskRow(
                onClick: (() -> Unit)? = null,
            ) {
                onClick?.invoke()
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(0, findings.size)
    }

    @Test
    fun `non-empty onClick body is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            @Composable
            fun TaskRow(
                onClick: () -> Unit = { doSomething() },
            ) {
                onClick()
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(0, findings.size)
    }
}
