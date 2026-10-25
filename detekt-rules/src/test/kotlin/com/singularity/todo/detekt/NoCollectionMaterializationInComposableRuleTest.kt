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
 * Positive and negative controls for [NoCollectionMaterializationInComposableRule].
 *
 * The rule's subject is a `@Composable` that materialises a collection in its
 * own body. A narrow rule is easy to write so that it never fires, and easy
 * to write so that it fires everywhere — both failure modes are pinned here:
 * the direct materialisation is reported, and the three cases that must stay
 * quiet (a `remember` block, an event handler, a `@Preview`) are not.
 *
 * The fixture is compiled through the **Path** overload of
 * `compileContentForTest`, not the `(content, packageName)` one: the string
 * overload wraps the content in a `KtScript`, and a rule that walks the
 * declarations sees nothing at all — a negative test on such a fixture passes
 * vacuously.
 */
class NoCollectionMaterializationInComposableRuleTest {

    private val rule = NoCollectionMaterializationInComposableRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    private fun findings(code: String) =
        rule.visitFile(
            compileContentForTest(code.trimIndent(), Path.of("Fixture.kt")),
            languageSettings,
        )

    @Test
    fun `a collection materialised in the composable body is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Composable
            import kotlinx.collections.immutable.toImmutableList

            @Composable
            fun TaskRow(ids: List<String>) {
                val persistent = ids.toImmutableList()
            }
        """
        val found = findings(code)
        assertEquals(1, found.size)
        assertTrue(found[0].message.contains("toImmutableList"))
    }

    @Test
    fun `a collection handed to a child composable is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Composable
            import kotlinx.collections.immutable.toImmutableList

            @Composable
            fun TaskRow(ids: List<String>) {
                Child(items = ids.toImmutableList())
            }

            @Composable
            fun Child(items: List<String>) {
            }
        """
        val found = findings(code)
        assertEquals(1, found.size)
    }

    @Test
    fun `a collection built inside remember is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.remember
            import kotlinx.collections.immutable.toImmutableList

            @Composable
            fun TaskRow(ids: List<String>) {
                val persistent = remember(ids) { ids.toImmutableList() }
            }
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `a collection built in an event handler is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Composable
            import kotlinx.collections.immutable.toImmutableList

            @Composable
            fun TaskRow(ids: List<String>, onPick: (List<String>) -> Unit) {
                Button(onClick = { onPick(ids.toImmutableList()) })
            }

            @Composable
            fun Button(onClick: () -> Unit) {
            }
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `a preview is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Composable
            import androidx.compose.ui.tooling.preview.Preview
            import kotlinx.collections.immutable.toImmutableList

            @Preview
            @Composable
            fun TaskRowPreview() {
                val persistent = listOf("a").toImmutableList()
            }
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `a collection built in a content lambda is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Composable
            import kotlinx.collections.immutable.toImmutableList

            @Composable
            fun TaskRow(ids: List<String>) {
                Scaffold {
                    Child(items = ids.toImmutableList())
                }
            }

            @Composable
            fun Scaffold(content: @Composable () -> Unit) {
            }

            @Composable
            fun Child(items: List<String>) {
            }
        """
        assertEquals(1, findings(code).size)
    }

    @Test
    fun `a collection built in a derived state is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.derivedStateOf
            import androidx.compose.runtime.remember
            import kotlinx.collections.immutable.toImmutableList

            @Composable
            fun TaskRow(ids: List<String>) {
                val persistent = remember { derivedStateOf { ids.toImmutableList() } }
            }
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `a non-composable function is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import kotlinx.collections.immutable.toImmutableList

            fun buildRows(ids: List<String>) = ids.toImmutableList()
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `a state property built once is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.mutableStateOf
            import androidx.compose.runtime.remember
            import kotlinx.collections.immutable.toImmutableList

            @Composable
            fun TaskRow(ids: List<String>) {
                var persistent by remember { mutableStateOf(ids.toImmutableList()) }
            }
        """
        assertEquals(0, findings(code).size)
    }
}
