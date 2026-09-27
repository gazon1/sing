package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoCombineSideEffectRuleTest {

    private val rule = NoCombineSideEffectRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    private fun findingsFor(body: String) =
        rule.visitFile(compileContentForTest(body, "com.example"), languageSettings)

    // ── Positive: real side effects ────────────────────────────────────────

    @Test
    fun `state flow assignment inside combine transform is flagged`() {
        val findings = findingsFor(
            """
            package com.example

            fun build() = combine(flowA, flowB) { a, b ->
                _state.value = a
                State(a, b)
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("Assigning to a StateFlow"))
    }

    @Test
    fun `seed call inside combine transform is flagged`() {
        val findings = findingsFor(
            """
            package com.example

            fun build() = combine(flowA, flowB) { a, b ->
                draftState.seed(a, b)
                State(a, b)
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("seed"))
    }

    @Test
    fun `channel send inside combine transform is flagged`() {
        val findings = findingsFor(
            """
            package com.example

            fun build() = combine(flowA, flowB) { a, b ->
                _events.send(Event(a))
                State(a, b)
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `launchIn inside combine transform is flagged`() {
        val findings = findingsFor(
            """
            package com.example

            fun build() = combine(flowA, flowB) { a, b ->
                repo.observe().launchIn(scope)
                State(a, b)
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `side effect in named transform argument is flagged`() {
        val findings = findingsFor(
            """
            package com.example

            fun build() = combine(flowA, flowB, transform = { a, b ->
                _state.value = a
                State(a, b)
            })
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `plus-assign inside combine transform is flagged`() {
        val findings = findingsFor(
            """
            package com.example

            fun build() = combine(flowA, flowB) { a, b ->
                _counter.value += 1
                State(a, b)
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun `combineStates transform is also checked`() {
        val findings = findingsFor(
            """
            package com.example

            fun build() = combineStates(flowA, flowB) { a, b ->
                _state.value = a
                State(a, b)
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size)
    }

    // ── Negative: pure transforms must not trip the rule ───────────────────

    @Test
    fun `pure data class construction is not flagged`() {
        val findings = findingsFor(
            """
            package com.example

            data class State(val a: String, val b: String)

            fun build() = combine(flowA, flowB) { a, b -> State(a, b) }
            """.trimIndent(),
        )
        assertEquals(0, findings.size)
    }

    @Test
    fun `reading state flow value is not flagged`() {
        val findings = findingsFor(
            """
            package com.example

            fun build() = combine(flowA, flowB) { a, b ->
                State(a, b, _linked.value)
            }
            """.trimIndent(),
        )
        assertEquals(0, findings.size)
    }

    @Test
    fun `conditional selection is not flagged`() {
        val findings = findingsFor(
            """
            package com.example

            fun build() = combine(flowA, flowB) { a, b -> if (a.isEmpty()) b else a }
            """.trimIndent(),
        )
        assertEquals(0, findings.size)
    }

    @Test
    fun `call to a pure helper is not flagged`() {
        val findings = findingsFor(
            """
            package com.example

            fun pureHelper(a: String, b: String): String = a + b

            fun build() = combine(flowA, flowB) { a, b -> pureHelper(a, b) }
            """.trimIndent(),
        )
        assertEquals(0, findings.size)
    }

    @Test
    fun `side effect outside the combine transform is not flagged`() {
        val findings = findingsFor(
            """
            package com.example

            fun build(scope: CoroutineScope) {
                combine(flowA, flowB) { a, b -> State(a, b) }
                    .collect { _state.value = it }
            }
            """.trimIndent(),
        )
        assertEquals(0, findings.size)
    }

    @Test
    fun `side effect in a launch block is not flagged`() {
        val findings = findingsFor(
            """
            package com.example

            fun build(scope: CoroutineScope) {
                scope.launch {
                    _state.value = 1
                }
            }
            """.trimIndent(),
        )
        assertEquals(0, findings.size)
    }

    @Test
    fun `side effect in a flatMapLatest transform is not flagged`() {
        val findings = findingsFor(
            """
            package com.example

            fun build() = flowA.flatMapLatest { a ->
                _latest.value = a
                flowB
            }
            """.trimIndent(),
        )
        assertEquals(0, findings.size)
    }
}
