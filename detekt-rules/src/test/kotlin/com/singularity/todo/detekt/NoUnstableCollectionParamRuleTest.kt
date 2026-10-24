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
 * Positive and negative controls for [NoUnstableCollectionParamRule].
 *
 * The rule's subject is narrow on purpose — a `@Composable` function taking a
 * raw collection — and a narrow rule is easy to write so that it never fires.
 * These tests therefore pin both halves: that the raw case is reported, and
 * that the three cases that must stay quiet (a persistent collection, a
 * non-composable function, a stable parameter) are not.
 *
 * The exemption is keyed by the *parameter*, so the register has two directions
 * to be wrong in, and both are pinned here: a raw parameter the register does
 * not name is a finding even at a known site, and a registered parameter the
 * composable no longer declares (or declares with another type) is a stale
 * entry — the debt it accounts for is gone, and the register still counts it.
 *
 * The fixture is compiled through the **Path** overload of
 * `compileContentForTest`, not the `(content, packageName)` one: the string
 * overload wraps the content in a `KtScript`, so `KtFile.declarations` is
 * `[KtScript]` and a rule that walks the declarations sees nothing at all. A
 * negative test on such a fixture passes vacuously, because "no findings" is
 * exactly what a mis-compiled fixture produces.
 */
class NoUnstableCollectionParamRuleTest {

    private val rule = NoUnstableCollectionParamRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    private fun findings(code: String) =
        rule.visitFile(
            compileContentForTest(code.trimIndent(), Path.of("Fixture.kt")),
            languageSettings,
        )

    @Test
    fun `a composable taking a List is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Composable

            @Composable
            fun TaskRow(tags: List<String>) {
            }
        """
        val found = findings(code)
        assertEquals(1, found.size)
        assertTrue(found[0].message.contains("List"))
    }

    @Test
    fun `a composable taking a Set or a Map is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Composable

            @Composable
            fun TaskRow(selected: Set<String>) {
            }

            @Composable
            fun TaskGrid(byDate: Map<String, Int>) {
            }
        """
        val found = findings(code)
        assertEquals(2, found.size)
    }

    @Test
    fun `a composable taking a persistent collection is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Composable
            import kotlinx.collections.immutable.ImmutableList

            @Composable
            fun TaskRow(tags: ImmutableList<String>) {
            }
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `a function that is not composable is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            fun buildRows(tags: List<String>): Int = tags.size
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `a stable parameter of a composable is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Composable

            @Composable
            fun TaskRow(title: String, count: Int, done: Boolean) {
            }
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `a nullable raw collection is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Composable

            @Composable
            fun TaskRow(tags: List<String>?) {
            }
        """
        assertEquals(1, findings(code).size)
    }

    @Test
    fun `a qualified type whose last segment is a collection name is not flagged`() {
        // `NotesRoute.List` is a nested route object, not a list. The rule reads
        // the last segment of the name, and a rule that called this a collection
        // would send the reader to convert a route into a persistent collection.
        val code = """
            package com.singularity.todo.feature.notes

            import androidx.compose.runtime.Composable

            sealed interface NotesRoute {
                data object List : NotesRoute
            }

            @Composable
            fun NotesListScreen(route: NotesRoute.List) {
            }
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `a finding the rule reports is not marked as suppressed`() {
        // A non-empty `suppressReasons` means "this finding is suppressed", and
        // detekt drops such a finding before it reaches the report. A rule that
        // marks its own findings suppressed is mute: the unit test sees the
        // finding (it calls the rule directly), the build sees nothing. The
        // rule test therefore has to look at the finding, not only at its count.
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Composable

            @Composable
            fun TaskRow(tags: List<String>) {
            }
        """
        val found = findings(code)
        assertEquals(1, found.size)
        assertTrue(
            found[0].suppressReasons.isEmpty(),
            "the rule marked its own finding as suppressed, so detekt would drop it: " +
                "the rule would report nothing in the build and everything here",
        )
    }

    @Test
    fun `the message names the persistent collection to use`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Composable

            @Composable
            fun TaskRow(tags: List<String>) {
            }
        """
        val found = findings(code)
        assertTrue(
            found[0].message.contains("ImmutableList"),
            "message should name the type to use instead, got: ${found[0].message}",
        )
    }

    @Test
    fun `a known site is exempt`() {
        // The register is keyed by the qualified name the Compose compiler
        // prints, so a site can be matched without a translation table. The
        // fixture declares the package and the name of a site the register
        // knows.
        val code = """
            package com.singularity.todo.core.ui.components

            import androidx.compose.runtime.Composable

            @Composable
            fun BlockedCompletionDialog(blockerTitles: List<String>) {
            }
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `a new composable in a known package is not exempt`() {
        // The exemption names a function, not a package: a new composable next
        // to a known one is still a finding.
        val code = """
            package com.singularity.todo.core.ui.components

            import androidx.compose.runtime.Composable

            @Composable
            fun BrandNewDialog(blockerTitles: List<String>) {
            }
        """
        assertEquals(1, findings(code).size)
    }

    @Test
    fun `a raw parameter a known site does not name is flagged`() {
        // The exemption is keyed by the parameter, not by the composable: a raw
        // collection parameter added to a known site is a finding, because the
        // register does not account for it.
        val code = """
            package com.singularity.todo.core.ui.components

            import androidx.compose.runtime.Composable

            @Composable
            fun BlockedCompletionDialog(blockerTitles: List<String>, alsoBlocked: List<String>) {
            }
        """
        val found = findings(code)
        assertEquals(1, found.size)
        assertTrue(found[0].message.contains("alsoBlocked") || found[0].message.contains("raw"))
    }

    @Test
    fun `a registered parameter that became a persistent collection is stale`() {
        // The debt is paid when the parameter becomes persistent, and the
        // register has to hear about it: a check that only looked at the
        // parameters that are *still* raw would never notice.
        val code = """
            package com.singularity.todo.core.ui.components

            import androidx.compose.runtime.Composable
            import kotlinx.collections.immutable.ImmutableList

            @Composable
            fun BlockedCompletionDialog(blockerTitles: ImmutableList<String>) {
            }
        """
        val found = findings(code)
        assertEquals(1, found.size)
        assertTrue(
            found[0].message.contains("ImmutableList"),
            "the finding should name the type the code declares, got: ${found[0].message}",
        )
    }

    @Test
    fun `a registered parameter the composable no longer declares is not the rule's business`() {
        // A parameter that is gone has no declared type to compare, and a
        // composable may have overloads — so "this parameter is missing here"
        // is not the same as "this parameter is gone". The gate answers that
        // question, because it reads the whole repository.
        val code = """
            package com.singularity.todo.core.ui.components

            import androidx.compose.runtime.Composable

            @Composable
            fun BlockedCompletionDialog() {
            }
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `a registered parameter declared with another type is stale`() {
        val code = """
            package com.singularity.todo.core.ui.components

            import androidx.compose.runtime.Composable

            @Composable
            fun BlockedCompletionDialog(blockerTitles: Set<String>) {
            }
        """
        val found = findings(code)
        assertEquals(1, found.size)
        assertTrue(
            found[0].message.contains("Set<String>"),
            "the finding should name the type the code declares, got: ${found[0].message}",
        )
    }

    @Test
    fun `a nested composable is named the way the compiler names it`() {
        // The Compose compiler prints the classes a function is nested in first
        // and the function last: `…feature.nav.Nav3State.toDecoratedEntries`.
        // The register is keyed by that name, so a rule that computed it the
        // other way round would not find the entry — and would report a site
        // the register accounts for.
        val code = """
            package com.singularity.todo.feature.nav

            import androidx.compose.runtime.Composable

            class Nav3State {
                @Composable
                fun toDecoratedEntries(entryDecorators: List<NavEntryDecorator<NavKey>>) {
                }
            }
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `a registered type spelled with a package qualifier is the same type`() {
        // The source spells the element type fully qualified; the register
        // spells it short. They are the same type, and a rule that compared the
        // text would call a living entry stale.
        val code = """
            package com.singularity.todo.feature.nav

            import androidx.compose.runtime.Composable

            class Nav3State {
                @Composable
                fun toDecoratedEntries(
                    entryDecorators: List<androidx.navigation3.runtime.NavEntryDecorator<NavKey>>,
                ) {
                }
            }
        """
        assertEquals(0, findings(code).size)
    }
}
