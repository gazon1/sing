package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoSwallowedCancellationTest {

    private val rule = NoSwallowedCancellation(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    @Test
    fun `catch with CancellationException rethrow is not flagged`() {
        val code = """
            package com.example
            import kotlin.coroutines.CancellationException
            suspend fun doWork() {
                try {
                    riskyCall()
                } catch (e: CancellationException) {
                    throw e
                }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(0, findings.size, "CE rethrow should not be flagged")
    }

    @Test
    fun `catch Exception without CE guard is flagged`() {
        val code = """
            package com.example
            suspend fun doWork() {
                try {
                    riskyCall()
                } catch (e: Exception) {
                    handleError(e)
                }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("catch (e: Exception)"))
    }

    @Test
    fun `catch Throwable in non-suspend is not flagged`() {
        val code = """
            package com.example
            fun doWork() {
                try {
                    riskyCall()
                } catch (e: Throwable) {
                    handleError(e)
                }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(0, findings.size, "Non-suspend functions are not checked")
    }

    @Test
    fun `catch CE then catch Throwable is safe when CE guard comes first`() {
        val code = """
            package com.example
            import kotlin.coroutines.CancellationException
            suspend fun doWork() {
                try {
                    riskyCall()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    handleError(e)
                }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(0, findings.size, "CE guard protects subsequent catches in same try")
    }

    @Test
    fun `runCatchingCancellable is safe`() {
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
