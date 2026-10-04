package com.singularity.todo.detekt

import dev.detekt.api.RuleName
import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Positive controls for the `kdoc-enforcement` rule set.
 *
 * Added 2026-10-04. This was the last rule class in `detekt-rules/` with no test
 * at all, and the same change had just switched both rules from `root.declarations`
 * (top-level only) to a full PSI tree walk — a fix with no regression guard.
 *
 * The rules are `private` in their own file, so they are reached through the
 * provider rather than constructed directly.
 */
class KDocEnforcementRulesTest {

    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    private fun rule(name: String) =
        KDocEnforcementRulesProvider().instance()
            .rules.getValue(RuleName(name))
            .invoke(TestConfig())

    private val viewModelRule get() = rule("ViewModelMustHaveKDoc")
    private val repositoryRule get() = rule("RepositoryInterfaceMustHaveKDoc")

    private fun count(rule: dev.detekt.api.Rule, code: String): Int {
        val ktFile = compileContentForTest(code.trimIndent(), "com.example")
        return rule.visitFile(ktFile, languageSettings).size
    }

    // ── ViewModelMustHaveKDoc ───────────────────────────────────────────────

    @Test
    fun `top-level ViewModel without KDoc is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            class TaskViewModel {
                val tasks = 0
            }
        """
        assertEquals(1, count(viewModelRule, code))
    }

    @Test
    fun `nested ViewModel without KDoc is flagged`() {
        // The regression guard for the tree-walk fix. `root.declarations` returns
        // only top-level declarations, so before 2026-10-04 this reported 0.
        val code = """
            package com.singularity.todo.feature.tasks

            object TaskHolder {
                class TaskViewModel {
                    val tasks = 0
                }
            }
        """
        assertEquals(1, count(viewModelRule, code))
    }

    @Test
    fun `deeply nested ViewModel without KDoc is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            object Outer {
                object Middle {
                    class TaskViewModel {
                        val tasks = 0
                    }
                }
            }
        """
        assertEquals(1, count(viewModelRule, code))
    }

    @Test
    fun `ViewModel with KDoc is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            /** Holds the task list state. */
            class TaskViewModel {
                val tasks = 0
            }
        """
        assertEquals(0, count(viewModelRule, code))
    }

    @Test
    fun `both top-level and nested ViewModels are counted`() {
        val code = """
            package com.singularity.todo.feature.tasks

            class TaskViewModel {
                val tasks = 0
            }

            object Holder {
                class ProjectViewModel {
                    val projects = 0
                }
            }
        """
        assertEquals(2, count(viewModelRule, code))
    }

    @Test
    fun `non-ViewModel class is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            class TaskService {
                val tasks = 0
            }
        """
        assertEquals(0, count(viewModelRule, code))
    }

    // ── RepositoryInterfaceMustHaveKDoc ─────────────────────────────────────

    @Test
    fun `repository interface without KDoc is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            interface TaskRepository {
                fun all(): List<String>
            }
        """
        assertEquals(1, count(repositoryRule, code))
    }

    @Test
    fun `nested repository interface without KDoc is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            object Ports {
                interface TaskRepository {
                    fun all(): List<String>
                }
            }
        """
        assertEquals(1, count(repositoryRule, code))
    }

    @Test
    fun `repository interface with KDoc is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            /** Reads and writes tasks. */
            interface TaskRepository {
                fun all(): List<String>
            }
        """
        assertEquals(0, count(repositoryRule, code))
    }

    @Test
    fun `repository implementation class is not flagged`() {
        // Only interfaces are policed; the impl class name ends with RepositoryImpl
        // and is not an interface, so it is out of scope by design.
        val code = """
            package com.singularity.todo.feature.tasks

            class TaskRepositoryImpl {
                fun all(): List<String> = emptyList()
            }
        """
        assertEquals(0, count(repositoryRule, code))
    }
}
