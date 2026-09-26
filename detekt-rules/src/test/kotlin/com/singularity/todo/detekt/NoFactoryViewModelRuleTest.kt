package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoFactoryViewModelRuleTest {

    private val rule = NoFactoryViewModelRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    private fun findingsFor(code: String) =
        rule.visitFile(compileContentForTest(code, "com.example"), languageSettings)

    @Test
    fun `factory with ViewModel constructor is flagged`() {
        val code = """
            package com.singularity.todo.core.di

            import org.koin.core.module.dsl.factory

            val module = module {
                factory { TasksViewModel(get()) }
            }
        """.trimIndent()
        val findings = findingsFor(code)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("viewModel"))
    }

    @Test
    fun `factory with deeply nested ViewModel constructor is flagged`() {
        val code = """
            package com.singularity.todo.core.di

            val module = module {
                factory { deps ->
                    TasksViewModel(deps.repo, deps.clock)
                }
            }
        """.trimIndent()
        assertEquals(1, findingsFor(code).size)
    }

    @Test
    fun `factoryOf with ViewModel reference is flagged`() {
        val code = """
            package com.singularity.todo.core.di

            val module = module {
                factoryOf(::TasksViewModel)
            }
        """.trimIndent()
        assertEquals(1, findingsFor(code).size)
    }

    @Test
    fun `viewModel registrations are not flagged`() {
        val code = """
            package com.singularity.todo.core.di

            val module = module {
                viewModel { TasksViewModel(get()) }
                viewModelOf(::TasksViewModel)
                viewModel { (initialDueDate: String) -> TaskCreateViewModel(initialDueDate, get()) }
            }
        """.trimIndent()
        assertEquals(0, findingsFor(code).size)
    }

    @Test
    fun `factory with non-ViewModel types is not flagged`() {
        val code = """
            package com.singularity.todo.core.di

            val module = module {
                factory { CreateTaskUseCase(get(), get()) }
                factory { Logger.withTag("App") }
                factory { createViewModelFactory() }
            }
        """.trimIndent()
        assertEquals(0, findingsFor(code).size)
    }
}
