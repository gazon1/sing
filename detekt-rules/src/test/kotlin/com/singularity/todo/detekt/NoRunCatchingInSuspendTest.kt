package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoRunCatchingInSuspendTest {

    private val rule = NoRunCatchingInSuspend(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    @Test
    fun `runCatching in suspend function is flagged`() {
        val code = """
            package com.example
            import kotlin.runCatching
            suspend fun doWork() {
                runCatching { riskyCall() }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("runCatching is not allowed inside a suspend function"))
    }

    @Test
    fun `runCatching in non-suspend is not flagged`() {
        val code = """
            package com.example
            import kotlin.runCatching
            fun doWork() {
                runCatching { riskyCall() }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(0, findings.size, "Non-suspend functions are not checked")
    }

    @Test
    fun `runCatchingCancellable in suspend is not flagged`() {
        val code = """
            package com.example
            import kotlin.coroutines.CancellationException
            suspend fun doWork() {
                runCatchingCancellable { riskyCall() }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(0, findings.size, "runCatchingCancellable is safe")
    }
}
