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
 * Positive and negative controls for [NoFalseImmutableRule].
 *
 * The rule guards a promise the compiler cannot check, so the question it
 * answers is narrow: does the class carry `@Immutable`, and does it carry
 * something that makes the promise false?
 *
 * Both halves are pinned. The positive controls are the two ways a promise
 * breaks — a mutable property and a raw collection. The negative controls are
 * the cases that must stay quiet: a class without the annotation, a class with
 * only stable values, a persistent collection, a `@Stable` class with
 * observable state, and a qualified name whose last segment happens to be
 * spelled `List`.
 *
 * The fixture is compiled through the **Path** overload of
 * `compileContentForTest`, not the `(content, packageName)` one: the string
 * overload wraps the content in a `KtScript`, so `KtFile.declarations` is
 * `[KtScript]` and a rule that walks the declarations sees nothing at all. A
 * negative test on such a fixture passes vacuously, because "no findings" is
 * exactly what a mis-compiled fixture produces.
 */
class NoFalseImmutableRuleTest {

    private val rule = NoFalseImmutableRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    private fun findings(code: String) =
        rule.visitFile(
            compileContentForTest(code.trimIndent(), Path.of("Fixture.kt")),
            languageSettings,
        )

    @Test
    fun `an immutable class with a var is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Immutable

            @Immutable
            class TaskBadge {
                var count: Int = 0
            }
        """
        val found = findings(code)
        assertEquals(1, found.size)
        assertTrue(
            found[0].message.contains("count"),
            "the finding should name the property, got: ${found[0].message}",
        )
    }

    @Test
    fun `an immutable class with a raw collection is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Immutable

            @Immutable
            class TaskBadge {
                val tags: List<String> = emptyList()
            }
        """
        val found = findings(code)
        assertEquals(1, found.size)
        assertTrue(
            found[0].message.contains("List"),
            "the finding should name the collection, got: ${found[0].message}",
        )
    }

    @Test
    fun `an immutable data class with a raw collection is flagged`() {
        // The property of a data class is a constructor parameter, and a rule
        // that reads only the class body would call this class fieldless.
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Immutable

            @Immutable
            data class TaskBadge(val tags: List<String>)
        """
        assertEquals(1, findings(code).size)
    }

    @Test
    fun `an immutable class with a mutable set is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Immutable

            @Immutable
            class TaskBadge {
                val tags: MutableSet<String> = mutableSetOf()
            }
        """
        assertEquals(1, findings(code).size)
    }

    @Test
    fun `an immutable class with stable values is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Immutable

            @Immutable
            class TaskBadge(
                val title: String,
                val count: Int,
                val done: Boolean,
            )
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `an immutable class with a persistent collection is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Immutable
            import kotlinx.collections.immutable.ImmutableList

            @Immutable
            class TaskBadge(val tags: ImmutableList<String>)
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `a class without the annotation is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            class TaskBadge {
                var count: Int = 0
                val tags: List<String> = emptyList()
            }
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `a stable class with observable state is not flagged`() {
        // `@Stable` is a different promise: the instance may change, and the
        // state is read through the snapshot system. That is the ordinary
        // pattern for a holder of observable state, and the rule leaves it
        // alone on purpose.
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Stable
            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.mutableStateOf
            import androidx.compose.runtime.setValue

            @Stable
            class TaskBadge {
                var count: Int by mutableStateOf(0)
            }
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `a qualified type whose last segment is a collection name is not flagged`() {
        val code = """
            package com.singularity.todo.feature.notes

            import androidx.compose.runtime.Immutable

            sealed interface NotesRoute {
                data object List : NotesRoute
            }

            @Immutable
            class NoteBadge(val route: NotesRoute.List)
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `an immutable class with a var in the primary constructor is flagged`() {
        // The `var` of a constructor parameter is a property of the class, and
        // a rule that reads only the class body would call this class fieldless.
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Immutable

            @Immutable
            class TaskBadge(var count: Int)
        """
        val found = findings(code)
        assertEquals(1, found.size)
        assertTrue(
            found[0].message.contains("count"),
            "the finding should name the property, got: ${found[0].message}",
        )
    }

    @Test
    fun `an immutable class with a nullable raw collection is flagged`() {
        // Nullability does not make a collection stable: the field still holds
        // a list, and the list can still change.
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Immutable

            @Immutable
            class TaskBadge {
                val tags: List<String>? = null
            }
        """
        val found = findings(code)
        assertEquals(1, found.size)
        assertTrue(found[0].message.contains("List"))
    }

    @Test
    fun `an immutable class with a mutable or concrete collection is flagged`() {
        // The rule promises to report a *raw collection*, not only the `List`.
        // The mutable spellings — and `Array` — are the same promise broken in
        // other words.
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Immutable

            @Immutable
            class TaskBadge {
                val items: MutableList<String> = mutableListOf()
                val picked: MutableSet<String> = mutableSetOf()
                val counts: MutableMap<String, Int> = mutableMapOf()
                val wide: Collection<String> = emptyList()
                val buffer: Array<String> = emptyArray()
            }
        """
        assertEquals(5, findings(code).size)
    }

    @Test
    fun `the stdlib collection spelled with its package is flagged`() {
        // `kotlin.collections.List` is the same collection as `List`, and a rule
        // that missed the spelling would be a rule a developer could walk
        // around by writing the type out.
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Immutable

            @Immutable
            class TaskBadge {
                val tags: kotlin.collections.List<String> = emptyList()
            }
        """
        assertEquals(1, findings(code).size)
    }

    @Test
    fun `an immutable object with a raw collection is flagged`() {
        // An `object` is not a class in the PSI, and a rule that only visited
        // classes never looked at one.
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Immutable

            @Immutable
            object TaskBadge {
                val tags: List<String> = emptyList()
            }
        """
        assertEquals(1, findings(code).size)
    }

    @Test
    fun `a finding the rule reports is not marked as suppressed`() {
        // A non-empty `suppressReasons` means "this finding is suppressed", and
        // detekt drops such a finding before it reaches the report. A rule that
        // marks its own findings suppressed is mute: the unit test sees the
        // finding, the build sees nothing.
        val code = """
            package com.singularity.todo.feature.tasks

            import androidx.compose.runtime.Immutable

            @Immutable
            class TaskBadge {
                val tags: List<String> = emptyList()
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
}
