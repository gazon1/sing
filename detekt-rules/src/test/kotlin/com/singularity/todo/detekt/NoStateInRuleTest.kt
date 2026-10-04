package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Positive controls for [NoStateInRule].
 *
 * Added 2026-10-04 alongside the removal of the `@OptIn(CombineStateInReadThrough)`
 * exemption. That annotation exists nowhere in the repository, so the exemption was
 * unreachable and the rule's own KDoc instructed agents to apply an annotation that
 * would not compile. The test below pins the behaviour that replaces it: there is no
 * invisible hatch, and a deliberate suppression is visible in review instead.
 */
class NoStateInRuleTest {

    private val rule = NoStateInRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    @Test
    fun `stateIn is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            class TaskViewModel {
                val tasks = MutableStateFlow<List<String>>(emptyList())

                fun expose(scope: Any) = tasks.stateIn(scope)
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("stateIn"))
    }

    @Test
    fun `plain state flow is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            class TaskViewModel {
                val tasks = MutableStateFlow<List<String>>(emptyList())
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        assertEquals(0, rule.visitFile(ktFile, languageSettings).size)
    }

    @Test
    fun `finding points reviewers at a visible suppression`() {
        val code = """
            package com.singularity.todo.feature.tasks

            class TaskViewModel {
                fun expose(scope: Any) = MutableStateFlow(0).stateIn(scope)
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
        assertTrue(
            findings[0].message.contains("@Suppress(\"NoStateIn\")"),
            "message should name the visible suppression, got: ${findings[0].message}",
        )
        assertTrue(
            !findings[0].message.contains("CombineStateInReadThrough"),
            "the fictional opt-in must not be advertised any more",
        )
    }
}
