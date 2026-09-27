package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoOpUpdateStateRuleTest {

    private val rule = NoOpUpdateStateRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    private fun findings(code: String) =
        rule.visitFile(compileContentForTest(code.trimIndent(), "com.example"), languageSettings)

    @Test
    fun `implicit it reducer is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            class AgendaViewModel {
                fun onLoaded() {
                    flow.collect { updateState { it } }
                }
            }
        """
        val result = findings(code)
        assertEquals(1, result.size)
        assertTrue(result[0].message.contains("never leaves its Loading branch"))
    }

    @Test
    fun `explicitly named parameter echoed back is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            class AgendaViewModel {
                fun onLoaded() {
                    flow.collect { loaded -> updateState { state -> state } }
                }
            }
        """
        assertEquals(1, findings(code).size)
    }

    @Test
    fun `regression - the exact agenda shape that shipped the bug is flagged`() {
        val code = """
            package com.singularity.todo.feature.agenda

            class AgendaViewModel {
                fun init() {
                    todayFlow().flatMapLatest { today ->
                        repo.observeByFilter(TaskFilter.All)
                            .map { tasks ->
                                AgendaUiState.Loaded(
                                    sections = evaluate(tasks, today),
                                    today = today,
                                )
                            }
                    }
                        .collect { updateState { it } }
                }
            }
        """
        assertEquals(1, findings(code).size)
    }

    @Test
    fun `setState with a named collector is not flagged`() {
        val code = """
            package com.singularity.todo.feature.agenda

            class AgendaViewModel {
                fun init() {
                    flow.collect { loaded -> setState(loaded) }
                }
            }
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `setState with implicit it is not flagged`() {
        // `it` here is the collected element, not a state transform — the two are unrelated.
        val code = """
            package com.singularity.todo.feature.agenda

            class AgendaViewModel {
                fun init() {
                    flow.collect { setState(it) }
                }
            }
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `updateState that derives a new value is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            class TaskViewModel {
                fun onToggle() {
                    updateState { it.copy(isLoading = false) }
                }
            }
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `updateState over a different variable is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            class TaskViewModel {
                fun onToggle() {
                    updateState { current -> current.copy(isLoading = false) }
                }
            }
        """
        assertEquals(0, findings(code).size)
    }
}
