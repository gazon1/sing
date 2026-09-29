package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoDirectClockSystemRuleTest {

    private val rule = NoDirectClockSystemRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    @Test
    fun `ClockSystem dot call is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks.data

            class TaskRepository {
                fun now() = Clock.System.now()
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("Direct `Clock.System` is banned"))
    }

    @Test
    fun `ClockSystem now call is flagged`() {
        val code = """
            package com.singularity.todo.core.backup

            fun export(): Instant = Clock.System.now()
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
    }

    @Test
    fun `Clock dot System dot now is flagged`() {
        val code = """
            package com.singularity.todo.core.auth.oauth

            fun tokenExpiry() = Clock.System.now().toEpochMilliseconds()
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
    }

    @Test
    fun `injected Clock parameter is not flagged`() {
        // Injected Clock is the recommended pattern — never touches Clock.System directly
        val code = """
            package com.singularity.todo.core.sync

            class SyncEngine(private val clock: Clock) {
                fun now(): Instant = clock.now()
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(0, findings.size)
    }

    @Test
    fun `Clock System without dot call is not flagged`() {
        // Clock type reference without accessing .System
        val code = """
            package com.singularity.todo.core.platform

            fun format(clock: Clock) = clock.toString()
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(0, findings.size)
    }

    @Test
    fun `multiple ClockSystem references in same file are each flagged`() {
        val code = """
            package com.singularity.todo.core.auth.oauth

            class TokenManager {
                fun issuedAt() = Clock.System.now()
                fun expiresAt() = Clock.System.now().plusMillis(3600_000)
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(2, findings.size)
    }
}
