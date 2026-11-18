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
        val code =
            """
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
        val code =
            """
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
        val code =
            """
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
        val code =
            """
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
        val code =
            """
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
    fun `runCatching in inline lambda nested in launch is flagged`() {
        val code =
            """
            package com.example
            import kotlin.runCatching
            fun start(scope: CoroutineScope, items: List<Int>) {
                scope.launch {
                    items.forEach {
                        runCatching { riskyCall() }
                    }
                }
            }
            """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        assertEquals(1, rule.visitFile(ktFile, languageSettings).size)
    }

    @Test
    fun `runCatching in parenthesized inline lambda nested in launch is flagged`() {
        val code =
            """
            package com.example
            import kotlin.runCatching
            fun start(scope: CoroutineScope, items: List<Int>) {
                scope.launch {
                    items.forEach({
                        runCatching { riskyCall() }
                    })
                }
            }
            """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        assertEquals(1, rule.visitFile(ktFile, languageSettings).size)
    }

    @Test
    fun `runCatching in a non-suspending lambda is not flagged`() {
        // The polarity of the allowlist. `forEach` takes an ordinary lambda, so this one is
        // genuinely not on a coroutine and flagging it would be a false positive.
        val code =
            """
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
        val code =
            """
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
    fun `runCatching in inline lambda inside ordinary lambda remains a boundary`() {
        val code =
            """
            package com.example
            import kotlin.runCatching
            fun start(items: List<Int>, block: () -> Unit) {
                block {
                    items.forEach {
                        runCatching { riskyCall() }
                    }
                }
            }
            """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        assertEquals(
            0,
            rule.visitFile(ktFile, languageSettings).size,
            "unknown/non-suspending lambda owners stay boundaries",
        )
    }

    @Test
    fun `runCatching inside sequence builder in a non-suspend function is not flagged`() {
        // kotlin.sequences.sequence takes a restricted suspend lambda for yield(), not a coroutine
        // cancellation context; treating it as a suspend owner is a false positive.
        val code =
            """
            package com.example
            import kotlin.runCatching
            fun createSequence() = sequence {
                runCatching { riskyCall() }
            }
            """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        assertEquals(0, rule.visitFile(ktFile, languageSettings).size)
    }

    @Test
    fun `runCatching in deferred sequence map callback inside launch is not flagged`() {
        val code =
            """
            package com.example
            import kotlin.runCatching
            fun start(scope: CoroutineScope) {
                scope.launch {
                    sequenceOf(1).map {
                        runCatching { riskyCall() }
                    }
                }
            }
            """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        assertEquals(
            0,
            rule.visitFile(ktFile, languageSettings).size,
            "Sequence.map callback is deferred and must not be treated as inline immediate context",
        )
    }

    @Test
    fun `runCatching in Flow map callback inside launch is not flagged by name-only rule`() {
        // Deliberate trade-off without semantic resolution: `map` is ambiguous (deferred
        // Sequence/Iterable operators vs suspending Flow operators). Treating it as a boundary
        // avoids Sequence false positives but accepts this possible Flow false negative.
        val code =
            """
            package com.example
            import kotlin.runCatching
            fun start(scope: CoroutineScope, flow: Flow<Int>) {
                scope.launch {
                    flow.map { runCatching { riskyCall() } }
                }
            }
            """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        assertEquals(
            0,
            rule.visitFile(ktFile, languageSettings).size,
            "Flow.map is intentionally left unclassified here to avoid Sequence false positives",
        )
    }

    @Test
    fun `runCatching in custom non-inline same-named non-extension call in same file is not flagged`() {
        // Narrow heuristic: a same-file non-extension, non-inline declaration with the same name
        // is treated as a boundary to avoid an obvious custom shadow false positive.
        val code =
            """
            package com.example
            import kotlin.runCatching
            fun forEach(block: () -> Unit) { /* stores block for later */ }
            fun start(scope: CoroutineScope) {
                scope.launch {
                    forEach {
                        runCatching { riskyCall() }
                    }
                }
            }
            """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        assertEquals(
            0,
            rule.visitFile(ktFile, languageSettings).size,
            "same-file non-inline non-extension declaration should prevent traversal false positive",
        )
    }

    @Test
    fun `unrelated same-file non-inline extension does not suppress standard inline call detection`() {
        // Extension declarations are excluded from the heuristic to avoid suppressing detection
        // for unrelated overloads/calls in the same file.
        val code =
            """
            package com.example
            import kotlin.runCatching
            fun String.forEach(block: (Char) -> Unit) { /* unrelated deferred/custom extension */ }
            fun start(scope: CoroutineScope, items: List<Int>) {
                scope.launch {
                    items.forEach {
                        runCatching { riskyCall() }
                    }
                }
            }
            """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        assertEquals(
            1,
            rule.visitFile(ktFile, languageSettings).size,
            "unrelated same-file extension declaration must not disable detection for standard call",
        )
    }

    @Test
    fun `unrelated same-file non-inline top-level declaration does not suppress receiver-qualified inline call`() {
        val code =
            """
            package com.example
            import kotlin.runCatching
            fun forEach(block: () -> Unit) { /* unrelated non-extension declaration */ }
            fun start(scope: CoroutineScope, items: List<Int>) {
                scope.launch {
                    items.forEach {
                        runCatching { riskyCall() }
                    }
                }
            }
            """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        assertEquals(
            1,
            rule.visitFile(ktFile, languageSettings).size,
            "receiver-qualified call cannot resolve to a non-extension top-level declaration",
        )
    }

    @Test
    fun `runCatchingCancellable in a launch lambda is not flagged`() {
        val code =
            """
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
