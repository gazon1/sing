package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests [NoViewModelScopeInProductionRule].
 *
 * The rule traverses the PSI tree looking for `viewModelScope.launch`, `viewModelScope.async`,
 * and `viewModelScope.cancel` expressions inside classes.
 */
class NoViewModelScopeInProductionRuleTest {

    private val rule = NoViewModelScopeInProductionRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    @Test
    fun `viewModelScope dot launch is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks.presentation.viewmodel

            class MyViewModel {
                fun load() {
                    viewModelScope.launch { }
                }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.singularity.todo.feature.tasks.presentation.viewmodel")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size, "Expected 1 finding but got ${findings.size}: ${findings.map { it.message }}")
    }

    @Test
    fun `injected scope dot launch is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks.presentation.viewmodel

            class MyViewModel {
                fun load(scope: CoroutineScope) {
                    scope.launch { }
                }
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.singularity.todo.feature.tasks.presentation.viewmodel")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(0, findings.size)
    }

    @Test
    fun `viewModelScope toString is allowed`() {
        val code = """
            package com.singularity.todo.feature.tasks.presentation.viewmodel

            class MyViewModel {
                val description: String get() = viewModelScope.toString()
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.singularity.todo.feature.tasks.presentation.viewmodel")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(0, findings.size)
    }
}
