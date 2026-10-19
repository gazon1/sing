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
 * Tests for [NoDroppedUiStatePropertyRule].
 *
 * The rule protects one property of the app's UI states: a variant of a sealed
 * `*UiState` has to *carry* the properties the interface declares. The defect it
 * catches is the one the projects list had — a failure state that replaced the search
 * query with a constant, which cleared what the user had typed.
 */
class NoDroppedUiStatePropertyRuleTest {

    private val rule = NoDroppedUiStatePropertyRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    /**
     * Compiles the snippet and returns the rule's findings.
     *
     * The **Path** overload, never the `(content, packageName)` one: the string overload
     * wraps the content in a `KtScript`, so `root.declarations` is `[KtScript]` and no
     * `KtClass` is ever seen — the rule would report nothing and every negative test
     * would pass vacuously.
     */
    private fun findingsIn(code: String) =
        rule.visitFile(compileContentForTest(code.trimIndent(), Path.of("Fixture.kt")), languageSettings)

    // ── Reported ───────────────────────────────────────────────────────────────────

    @Test
    fun `a variant that replaces the property with a constant is flagged`() {
        val code = """
            package com.singularity.todo.feature.projects

            sealed interface ProjectsUiState {
                val searchQuery: String

                data class Error(
                    val message: String,
                ) : ProjectsUiState {
                    override val searchQuery: String = ""
                }
            }
        """
        val findings = findingsIn(code)
        assertEquals(1, findings.size, "expected one finding, got $findings")
        assertTrue(
            findings[0].message.contains("searchQuery"),
            "the finding must name the property, got ${findings[0].message}",
        )
    }

    @Test
    fun `a variant that does not declare the property is flagged`() {
        val code = """
            package com.singularity.todo.feature.projects

            sealed interface ProjectsUiState {
                val searchQuery: String

                data class Error(
                    val message: String,
                ) : ProjectsUiState
            }
        """
        val findings = findingsIn(code)
        assertEquals(1, findings.size, "expected one finding, got $findings")
        assertTrue(
            findings[0].message.contains("does not declare it"),
            "the finding must say what is wrong, got ${findings[0].message}",
        )
    }

    @Test
    fun `a variant that gives the property a default is flagged`() {
        val code = """
            package com.singularity.todo.feature.projects

            sealed interface ProjectsUiState {
                val searchQuery: String

                data class Error(
                    val message: String,
                    override val searchQuery: String = "",
                ) : ProjectsUiState
            }
        """
        val findings = findingsIn(code)
        assertEquals(1, findings.size, "expected one finding, got $findings")
        assertTrue(
            findings[0].message.contains("default value"),
            "the finding must say what is wrong, got ${findings[0].message}",
        )
    }

    @Test
    fun `every offending variant is reported`() {
        val code = """
            package com.singularity.todo.feature.projects

            sealed interface ProjectsUiState {
                val searchQuery: String
                val sortOrder: Int

                data class Error(
                    val message: String,
                ) : ProjectsUiState

                data class Empty(
                    override val searchQuery: String = "",
                ) : ProjectsUiState
            }
        """
        val findings = findingsIn(code)
        // `Error` drops both properties (two findings); `Empty` drops one and gives
        // the other a default (two more): one finding per problem, not per variant.
        assertEquals(4, findings.size, "expected one finding per problem, got $findings")
    }

    // ── Allowed ────────────────────────────────────────────────────────────────────

    @Test
    fun `a variant that declares the property in the constructor is allowed`() {
        val code = """
            package com.singularity.todo.feature.projects

            sealed interface ProjectsUiState {
                val searchQuery: String

                data class Content(
                    val projects: List<String>,
                    override val searchQuery: String,
                ) : ProjectsUiState
            }
        """
        assertEquals(emptyList(), findingsIn(code))
    }

    @Test
    fun `a UI state without interface properties is allowed`() {
        val code = """
            package com.singularity.todo.feature.archive

            sealed interface ArchiveUiState {
                data object Loading : ArchiveUiState
                data class Content(val tasks: List<String>) : ArchiveUiState
                data class Error(val message: String) : ArchiveUiState
            }
        """
        assertEquals(emptyList(), findingsIn(code))
    }

    @Test
    fun `an interface that is not sealed is not checked`() {
        val code = """
            package com.singularity.todo.feature.projects

            interface ProjectsUiState {
                val searchQuery: String
            }

            data class Error(
                val message: String,
            ) : ProjectsUiState
        """
        assertEquals(emptyList(), findingsIn(code))
    }

    @Test
    fun `an interface that is not a UI state is not checked`() {
        val code = """
            package com.singularity.todo.feature.projects

            sealed interface ProjectFilter {
                val query: String

                data class Search(
                    override val query: String = "",
                ) : ProjectFilter
            }
        """
        assertEquals(emptyList(), findingsIn(code))
    }

    @Test
    fun `a nested class that does not implement the interface is not checked`() {
        val code = """
            package com.singularity.todo.feature.projects

            sealed interface ProjectsUiState {
                val searchQuery: String

                data class Content(
                    override val searchQuery: String,
                ) : ProjectsUiState

                class Helper {
                    val searchQuery: String = ""
                }
            }
        """
        assertEquals(emptyList(), findingsIn(code))
    }
}
