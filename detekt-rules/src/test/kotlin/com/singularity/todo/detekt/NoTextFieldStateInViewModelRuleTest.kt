package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [NoTextFieldStateInViewModelRule].
 *
 * The rule is the storage half of the state-based text field migration: the field owns its
 * buffer, the ViewModel owns a `String`, and a `TextFieldState` in either the ViewModel or
 * the UI state is a second source of truth for the same text. Every negative case here is a
 * shape the app actually uses — a composable helper that takes the field's state, a local
 * `rememberTextFieldState()`, a ViewModel that keeps a `String` — so a false positive would
 * block the sanctioned shapes, not just annoy.
 */
class NoTextFieldStateInViewModelRuleTest {

    private val rule = NoTextFieldStateInViewModelRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    /**
     * Compiles the snippet and returns the rule's findings.
     *
     * The **Path** overload, never the `(content, packageName)` one: the string overload
     * wraps the content in a `KtScript`, so `root.declarations` is `[KtScript]` and no
     * `KtClass` is ever seen — a rule that walks the classes would report nothing, and
     * every negative test would pass vacuously.
     */
    private fun findingsIn(code: String) =
        rule.visitFile(compileContentForTest(code.trimIndent(), Path.of("Fixture.kt")), languageSettings)

    // ── Reported ───────────────────────────────────────────────────────────────────

    @Test
    fun `a ViewModel property of type TextFieldState is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            class TaskEditorViewModel {
                private val titleState: TextFieldState = TextFieldState()
            }
        """
        val findings = findingsIn(code)
        assertTrue(
            findings.any { it.message.contains("titleState") && it.message.contains("TextFieldState") },
            "expected the stored TextFieldState to be reported, got $findings",
        )
    }

    @Test
    fun `a ViewModel constructor parameter of type TextFieldState is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            class TaskEditorViewModel(
                private val titleState: TextFieldState,
            )
        """
        val findings = findingsIn(code)
        assertTrue(
            findings.any { it.message.contains("titleState") },
            "a constructor parameter is stored too, so it is reported, got $findings",
        )
    }

    @Test
    fun `a nullable TextFieldState property in a ViewModel is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            class TaskEditorViewModel {
                private val titleState: TextFieldState? = null
            }
        """
        assertTrue(
            findingsIn(code).any { it.message.contains("titleState") },
            "a nullable TextFieldState is the same defect",
        )
    }

    @Test
    fun `a collection of TextFieldState in a ViewModel is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            class TaskEditorViewModel {
                private val fieldStates: List<TextFieldState> = emptyList()
            }
        """
        assertTrue(
            findingsIn(code).any { it.message.contains("fieldStates") },
            "a collection of buffers is the same second source of truth",
        )
    }

    @Test
    fun `a ViewModel extending MviViewModel is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            class TaskEditor : MviViewModel<TaskUiState, TaskIntent, TaskEvent>() {
                private val titleState: TextFieldState = TextFieldState()
            }
        """
        assertTrue(
            findingsIn(code).any { it.message.contains("titleState") },
            "the MVI base class makes it a ViewModel whatever the class is called",
        )
    }

    @Test
    fun `a ViewModel storing a rememberTextFieldState result is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            class TaskEditorViewModel {
                private val titleState = rememberTextFieldState()
            }
        """
        assertTrue(
            findingsIn(code).any { it.message.contains("titleState") },
            "creating the state is right, storing it in the ViewModel is not",
        )
    }

    @Test
    fun `a UI state class with a TextFieldState property is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            sealed interface TaskUiState {
                data class Content(val titleState: TextFieldState) : TaskUiState
            }
        """
        assertTrue(
            findingsIn(code).any { it.message.contains("titleState") },
            "a mutable buffer inside an immutable UI state is the defect the ADR names",
        )
    }

    @Test
    fun `a UI state class in another file is flagged`() {
        // The interface is not declared here — the class only names it. The name is
        // enough to recognise a UI state, so the rule does not need to resolve the type
        // across files.
        val code = """
            package com.singularity.todo.feature.tasks

            data class Content(val titleState: TextFieldState) : TaskUiState
        """
        assertTrue(
            findingsIn(code).any { it.message.contains("titleState") },
            "a UI state declared in another file is the same defect as one declared next to it",
        )
    }

    // ── Not reported ───────────────────────────────────────────────────────────────

    @Test
    fun `a composable helper taking a TextFieldState is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            @Composable
            fun TaskTitleField(titleState: TextFieldState) {
                OutlinedTextField(state = titleState)
            }
        """
        assertEquals(
            0,
            findingsIn(code).size,
            "a composable that receives the field's state is the sanctioned shape",
        )
    }

    @Test
    fun `a local rememberTextFieldState is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            @Composable
            fun TaskTitle() {
                val titleState = rememberTextFieldState()
                OutlinedTextField(state = titleState)
            }
        """
        assertEquals(
            0,
            findingsIn(code).size,
            "the field's own state is created and owned by the composable",
        )
    }

    @Test
    fun `a ViewModel with a String property is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            class TaskEditorViewModel {
                private val title: String = ""
            }
        """
        assertEquals(
            0,
            findingsIn(code).size,
            "a String in the ViewModel is what the ViewModel is supposed to keep",
        )
    }

    @Test
    fun `a ViewModel exposing a MutableStateFlow of a UI state with a String is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            sealed interface TaskUiState {
                data class Content(val title: String) : TaskUiState
            }

            class TaskListViewModel {
                private val _state = MutableStateFlow<TaskUiState>(
                    TaskUiState.Content(title = ""),
                )
                val state: MutableStateFlow<TaskUiState> = _state
            }
        """
        assertEquals(
            0,
            findingsIn(code).size,
            "the canonical ViewModel shape holds a String in its UI state",
        )
    }

    @Test
    fun `a plain class holding a TextFieldState is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            class TaskTitleFieldHolder {
                val titleState: TextFieldState = TextFieldState()
            }
        """
        assertEquals(
            0,
            findingsIn(code).size,
            "a class that is neither a ViewModel nor a UI state is out of scope",
        )
    }

    @Test
    fun `a function parameter of type TextFieldState is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            fun renderTitle(titleState: TextFieldState): String = titleState.text
        """
        assertEquals(
            0,
            findingsIn(code).size,
            "ordinary code may pass the field's state around",
        )
    }
}
