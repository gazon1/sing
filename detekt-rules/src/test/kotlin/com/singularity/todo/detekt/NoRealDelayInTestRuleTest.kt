package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Positive controls for [NoRealDelayInTestRule].
 *
 * Added 2026-10-04. The rule had no test at all, and it had two silent
 * pass-throughs: `toLongOrNull()` cannot parse `1_000` or `1000L`, so the
 * spellings Kotlin code actually uses bypassed the check entirely.
 */
class NoRealDelayInTestRuleTest {

    private val rule = NoRealDelayInTestRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    private fun findingsFor(delayArg: String): Int {
        val code = """
            package com.singularity.todo.test

            class FlowTest {
                fun wait() {
                    delay($delayArg)
                }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        return rule.visitFile(ktFile, languageSettings).size
    }

    @Test
    fun `plain long delay is flagged`() {
        assertEquals(1, findingsFor("1000"))
    }

    @Test
    fun `underscored delay literal is flagged`() {
        // The spelling that bypassed the old toLongOrNull() path.
        assertEquals(1, findingsFor("1_000"))
    }

    @Test
    fun `long-suffixed delay literal is flagged`() {
        // Also bypassed before. The repo really does contain delay(1000L).
        assertEquals(1, findingsFor("1000L"))
    }

    @Test
    fun `underscored and suffixed delay literal is flagged`() {
        assertEquals(1, findingsFor("1_000L"))
    }

    @Test
    fun `short delay is not flagged`() {
        assertEquals(0, findingsFor("100"))
    }

    @Test
    fun `delay at the threshold is not flagged`() {
        assertEquals(0, findingsFor("500"))
    }

    @Test
    fun `non-literal delay is not flagged`() {
        // We cannot know the value; reporting would be a guess.
        assertEquals(0, findingsFor("someConstant"))
    }

    @Test
    fun `message quotes the original spelling`() {
        val code = """
            package com.singularity.todo.test

            class FlowTest {
                fun wait() {
                    delay(1_000)
                }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
        assertTrue(
            findings[0].message.contains("1_000"),
            "message should quote the literal as written, got: ${findings[0].message}",
        )
    }

    @Test
    fun `Thread_sleep is flagged`() {
        val code = """
            package com.singularity.todo.test

            class FlowTest {
                fun wait() {
                    Thread.sleep(1_000)
                }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        assertEquals(1, rule.visitFile(ktFile, languageSettings).size)
    }

    @Test
    fun `Thread_sleep zero is not flagged`() {
        val code = """
            package com.singularity.todo.test

            class FlowTest {
                fun wait() {
                    Thread.sleep(0)
                }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        assertEquals(0, rule.visitFile(ktFile, languageSettings).size)
    }
}
