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
        assertTrue(findings[0].message.contains("runCatching is not allowed inside a suspend context"))
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

    @Test
    fun `runCatching in a launch lambda is flagged`() {
        // The shape that got through. The activation note in detekt.yml says the tree reported
        // zero violations after the migration; it reported zero because the walk stopped at
        // named functions, and `SyncPrefs` carries exactly this.
        val code = """
            package com.example
            import kotlin.runCatching
            fun start(scope: CoroutineScope) {
                scope.launch {
                    runCatching { riskyCall() }
                }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("runCatching is not allowed inside a suspend context"))
    }

    @Test
    fun `runCatching in an async lambda is flagged`() {
        val code = """
            package com.example
            import kotlin.runCatching
            fun start(scope: CoroutineScope) = scope.async {
                runCatching { riskyCall() }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        assertEquals(1, rule.visitFile(ktFile, languageSettings).size)
    }

    @Test
    fun `runCatching in a non-suspending lambda is not flagged`() {
        // The polarity of the allowlist. `forEach` takes an ordinary lambda, so this one is
        // genuinely not on a coroutine and flagging it would be a false positive.
        val code = """
            package com.example
            import kotlin.runCatching
            fun run(items: List<Int>) {
                items.forEach {
                    runCatching { it.toString() }
                }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        assertEquals(
            0,
            rule.visitFile(ktFile, languageSettings).size,
            "a forEach lambda is not a coroutine boundary",
        )
    }

    @Test
    fun `runCatching below a non-suspend function inside a launch lambda is not flagged`() {
        // The walk stops at the first boundary in either direction. A plain function nested
        // inside a suspend lambda is a new frame and must not inherit from its caller.
        val code = """
            package com.example
            import kotlin.runCatching
            fun start(scope: CoroutineScope) {
                scope.launch {
                    helper()
                }
            }
            fun helper() {
                runCatching { riskyCall() }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        assertEquals(0, rule.visitFile(ktFile, languageSettings).size)
    }

    @Test
    fun `runCatchingCancellable in a launch lambda is not flagged`() {
        val code = """
            package com.example
            fun start(scope: CoroutineScope) {
                scope.launch {
                    runCatchingCancellable { riskyCall() }
                }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        assertEquals(0, rule.visitFile(ktFile, languageSettings).size)
    }
}
