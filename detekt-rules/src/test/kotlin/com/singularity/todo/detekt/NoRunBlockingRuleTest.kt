package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoRunBlockingRuleTest {

    private val rule = NoRunBlockingRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    @Test
    fun `runBlocking with trailing lambda is flagged`() {
        val code = """
            package com.singularity.todo.core.di

            fun createScope() {
                runBlocking { delay(100) }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("runBlocking is banned"))
    }

    @Test
    fun `runBlocking with explicit lambda argument is flagged`() {
        val code = """
            package com.singularity.todo.core.settings

            fun loadPreferences() {
                runBlocking { block ->
                    block()
                }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
    }

    @Test
    fun `scope launch is not flagged`() {
        val code = """
            package com.singularity.todo.core.di

            fun createScope(scope: CoroutineScope) {
                scope.launch { }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(0, findings.size)
    }
}
