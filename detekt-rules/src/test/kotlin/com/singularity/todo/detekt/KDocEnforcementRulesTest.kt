package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import java.nio.file.Path
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

    private val viewModelRule = ViewModelMustHaveKDocRule(TestConfig())
    private val repositoryRule = RepositoryInterfaceMustHaveKDocRule(TestConfig())

    // The Path overload, not the String one. `compileContentForTest(code, "pkg")`
    // wraps the snippet in a KtScript, and a script's `declarations` are not the
    // file's classes — so every "is flagged" case below returned 0 and every "is
    // not flagged" case passed for the same reason: the rule was never reaching the
    // fixture. A test class whose only passing tests are the negative controls is
    // a control that cannot fail. RuleFiresSmokeTest uses the same Path form.
    private fun count(rule: dev.detekt.api.Rule, code: String): Int {
        val ktFile = compileContentForTest(code.trimIndent(), Path.of("Fixture.kt"))
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
    fun `ViewModel with KDoc above a parameterised constructor is not flagged`() {
        // The convention in this repo: the KDoc sits immediately above
        // `class Foo( … )`. PSI attaches that to the *primary constructor*, so
        // `clazz.docComment` is null and the rule reported two fully documented
        // ViewModels (AppVersionGateViewModel, TagGroupsViewModel) as undocumented.
        val code = """
            package com.singularity.todo.feature.tasks

            /**
             * ViewModel for the task list.
             *
             * Watches the repository and maps to UiState.
             */
            class TaskViewModel(
                private val repository: TaskRepository,
            ) {
                val tasks = 0
            }
        """
        assertEquals(0, count(viewModelRule, code))
    }

    @Test
    fun `ViewModel with parameters and no KDoc is still flagged`() {
        // The fix must not become a hole: no doc at all is still a violation.
        val code = """
            package com.singularity.todo.feature.tasks

            class TaskViewModel(
                private val repository: TaskRepository,
            ) {
                val tasks = 0
            }
        """
        assertEquals(1, count(viewModelRule, code))
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
