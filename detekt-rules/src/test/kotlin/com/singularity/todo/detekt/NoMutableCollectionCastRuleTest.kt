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
 * Positive and negative controls for [NoMutableCollectionCastRule].
 *
 * The rule protects the stability contract: a value the compiler has been told
 * is stable is compared by `equals`, so a collection mutated after a cast
 * keeps comparing equal to its old contents and the UI goes stale without an
 * error. The cast is the only way to break that from inside code the compiler
 * cannot see, so the cast is what is banned.
 *
 * The repository has no such cast today. The rule exists because the contract
 * is now written down, and a rule that only describes last year's mistakes is
 * a comment.
 */
class NoMutableCollectionCastRuleTest {

    private val rule = NoMutableCollectionCastRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    private fun findings(code: String) =
        rule.visitFile(
            compileContentForTest(code.trimIndent(), Path.of("Fixture.kt")),
            languageSettings,
        )

    @Test
    fun `casting to a MutableList is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            fun widen(source: List<String>): MutableList<String> = source as MutableList<String>
        """
        val found = findings(code)
        assertEquals(1, found.size)
        assertTrue(found[0].message.contains("MutableList"))
    }

    @Test
    fun `a finding the rule reports is not marked as suppressed`() {
        // A non-empty `suppressReasons` means "this finding is suppressed", and
        // detekt drops such a finding before it reaches the report. A rule that
        // marks its own findings suppressed is mute: the unit test sees the
        // finding (it calls the rule directly), the build sees nothing.
        val code = """
            package com.singularity.todo.feature.tasks

            fun widen(source: List<String>): MutableList<String> = source as MutableList<String>
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
    fun `a safe cast to a mutable collection is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            fun widen(source: List<String>): MutableList<String>? = source as? MutableList
        """
        assertEquals(1, findings(code).size)
    }

    @Test
    fun `a cast to a nullable mutable collection is flagged`() {
        // The cast target is `MutableList?`: the mutable collection is inside
        // the nullable type, and a rule that read only the type element would
        // miss every nullable cast.
        val code = """
            package com.singularity.todo.feature.tasks

            fun widen(source: List<String>): MutableList<String>? = source as MutableList<String>?
        """
        assertEquals(1, findings(code).size)
    }

    @Test
    fun `casting to a MutableSet and a MutableMap is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            fun toSet(source: List<String>): MutableSet<String> = source as MutableSet
            fun toMap(source: Map<String, Int>): MutableMap<String, Int> = source as MutableMap
        """
        assertEquals(2, findings(code).size)
    }

    @Test
    fun `casting to an immutable collection is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            import kotlinx.collections.immutable.ImmutableList

            fun narrow(source: List<String>): ImmutableList<String> = source as ImmutableList
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `an ordinary cast is not flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            fun widen(source: Any): String = source as String
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `the message names the immutable type to hand over`() {
        val code = """
            package com.singularity.todo.feature.tasks

            fun widen(source: List<String>): MutableList<String> = source as MutableList
        """
        val found = findings(code)
        assertTrue(
            found[0].message.contains("toImmutableList"),
            "message should name the way out, got: ${found[0].message}",
        )
    }
}
