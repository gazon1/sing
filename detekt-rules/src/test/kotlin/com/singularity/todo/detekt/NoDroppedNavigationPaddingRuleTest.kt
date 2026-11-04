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
 * Tests for [NoDroppedNavigationPaddingRule].
 *
 * The rule guards one thing: the shell's navigation-suite content lambda spends the
 * padding the bottom navigation bar took. The first fixture is the **pre-fix shell**,
 * copied from `AndroidShellNav3.kt` before #1699 — a lambda with no parameter and
 * horizontal-only insets — so the rule's failure on the real regression is a test, not a
 * claim in a KDoc.
 */
class NoDroppedNavigationPaddingRuleTest {
    private val rule = NoDroppedNavigationPaddingRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    /**
     * Compiles the snippet and returns the rule's findings.
     *
     * The **Path** overload, never the `(content, packageName)` one: the string overload
     * wraps the content in a `KtScript`, so the file's declarations are not what the
     * snippet declares.
     */
    private fun findingsIn(code: String) =
        rule.visitFile(
            compileContentForTest(code.trimIndent(), Path.of("Fixture.kt")),
            languageSettings,
        )

    // ── The regression the rule exists for ──────────────────────────────────────────

    @Test
    fun `the pre-fix shell, whose lambda takes no padding, is flagged`() {
        val code = """
            package com.singularity.todo.shell

            @Composable
            fun Shell() {
                NavigationSuiteScaffold(navigationSuiteItems = { }) {
                    Scaffold(
                        contentWindowInsets =
                            ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal),
                    ) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding))
                    }
                }
            }
        """
        val findings = findingsIn(code)
        assertEquals(1, findings.size, "expected one finding, got $findings")
        assertTrue(
            findings[0].message.contains("declares no padding parameter"),
            "the finding must name the dropped parameter, got ${findings[0].message}",
        )
    }

    @Test
    fun `a lambda that takes the padding and never reads it is flagged`() {
        val code = """
            package com.singularity.todo.shell

            @Composable
            fun Shell() {
                NavigationSuiteScaffold(navigationSuiteItems = { }) { navigationPadding ->
                    Scaffold(
                        contentWindowInsets =
                            ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal),
                    ) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding))
                    }
                }
            }
        """
        val findings = findingsIn(code)
        assertEquals(1, findings.size, "expected one finding, got $findings")
        assertTrue(
            findings[0].message.contains("navigationPadding"),
            "the finding must name the unused parameter, got ${findings[0].message}",
        )
    }

    // ── The fixed shell, and the other legitimate way to spend the padding ──────────

    @Test
    fun `the fixed shell, which folds the padding into contentWindowInsets, is quiet`() {
        val code = """
            package com.singularity.todo.shell

            @Composable
            fun Shell() {
                NavigationSuiteScaffold(navigationSuiteItems = { }) { navigationPadding ->
                    Scaffold(
                        contentWindowInsets =
                            shellContentWindowInsets(
                                horizontalInsets =
                                    ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal),
                                navigationPadding = navigationPadding,
                            ),
                    ) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding))
                    }
                }
            }
        """
        val findings = findingsIn(code)
        assertEquals(0, findings.size, "the fixed shell must not be flagged, got $findings")
    }

    @Test
    fun `a lambda that pads its content with the padding is quiet`() {
        val code = """
            package com.singularity.todo.shell

            @Composable
            fun Shell() {
                NavigationSuiteScaffold(navigationSuiteItems = { }) { navigationPadding ->
                    Box(Modifier.fillMaxSize().padding(navigationPadding)) {
                        Text("content")
                    }
                }
            }
        """
        val findings = findingsIn(code)
        assertEquals(0, findings.size, "padding the content is a legitimate spend, got $findings")
    }

    @Test
    fun `a scaffold that is not a navigation suite is not the rule's subject`() {
        val code = """
            package com.singularity.todo.shell

            @Composable
            fun Shell() {
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding))
                }
            }
        """
        val findings = findingsIn(code)
        assertEquals(0, findings.size, "only NavigationSuiteScaffold is in scope, got $findings")
    }
}
