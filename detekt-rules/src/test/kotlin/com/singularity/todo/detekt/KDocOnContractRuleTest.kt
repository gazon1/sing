package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KDocOnContractRuleTest {

    private val rule = KDocOnContractRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    @Test
    fun `Repository interface without KDoc is flagged`() {
        val code = """
            package com.example
            interface TaskRepository {
                suspend fun getTask(id: TaskId): Task?
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("Missing KDoc on Repository interface"))
    }

    @Test
    fun `Repository interface with KDoc is not flagged`() {
        val code = """
            package com.example
            /** Repository for task persistence. */
            interface TaskRepository {
                suspend fun getTask(id: TaskId): Task?
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(0, findings.size)
    }

    @Test
    fun `ViewModel without KDoc is flagged`() {
        val code = """
            package com.example
            class MyViewModel(
                private val repo: TaskRepository,
            ) {
                fun load() { }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("Missing KDoc on ViewModel"))
    }

    @Test
    fun `ViewModel with KDoc is not flagged`() {
        val code = """
            package com.example
            /** ViewModel for task list. */
            class MyViewModel(
                private val repo: TaskRepository,
            ) {
                fun load() { }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(0, findings.size)
    }

    @Test
    fun `expect class without KDoc is flagged`() {
        val code = """
            package com.example
            expect class Clock {
                fun now(): Instant
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("Missing KDoc on expect class"))
    }

    @Test
    fun `actual class without KDoc is flagged`() {
        val code = """
            package com.example
            actual class Clock {
                fun now(): Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("Missing KDoc on actual class"))
    }

    @Test
    fun `plain class without Repository or ViewModel suffix is not flagged`() {
        val code = """
            package com.example
            class MyHelper {
                fun help() { }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(0, findings.size)
    }
}
