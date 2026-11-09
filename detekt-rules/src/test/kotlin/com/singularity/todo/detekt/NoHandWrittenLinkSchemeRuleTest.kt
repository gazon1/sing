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
 * Positive and negative controls for [NoHandWrittenLinkSchemeRule].
 *
 * The rule has two halves, and both are pinned: a literal that names a scheme outside
 * the grammar's home is a finding, and the shapes that must stay quiet — a comment, a
 * home file, a `const val` declaration, a test source — are quiet.
 *
 * The fixture is compiled through the **Path** overload of `compileContentForTest`, so
 * the file has a real virtual-file path: the rule's production/test distinction reads
 * it, and a string overload would leave the path empty and skip every fixture.
 */
class NoHandWrittenLinkSchemeRuleTest {
    private val rule = NoHandWrittenLinkSchemeRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    private fun findings(
        code: String,
        path: String = "/repo/shared/src/commonMain/kotlin/Fixture.kt",
    ) = rule.visitFile(
        compileContentForTest(code.trimIndent(), Path.of(path)),
        languageSettings,
    )

    @Test
    fun `a literal that names a scheme is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            val href = "singularity://task/abc"
        """
        val found = findings(code)
        assertEquals(1, found.size)
        assertTrue(
            found[0].message.contains("singularity://"),
            "the finding should name the scheme, got: ${found[0].message}",
        )
    }

    @Test
    fun `a literal with an interpolated id is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks

            fun href(id: String) = "task://" + id
        """
        assertEquals(1, findings(code).size)
    }

    @Test
    fun `a triple-quoted literal is flagged`() {
        // The quotes are built by interpolation: inside a raw string `\"` is a
        // backslash and a quote, not an escaped quote, so the fixture would not
        // contain a triple-quoted literal at all.
        val code = """
            package com.singularity.todo.feature.tasks

            val html = ${"\"\"\""}<a href="note://n1">x</a>${"\"\"\""}
        """
        assertEquals(1, findings(code).size)
    }

    @Test
    fun `a mention in a comment or a KDoc is not a literal`() {
        val code = """
            package com.singularity.todo.feature.tasks

            // The format is task://<id>.
            /**
             * A link to `note://<id>` and to `singularity://task/<id>`.
             */
            val label = "a task"
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `a file that declares the grammar is the home`() {
        val code = """
            package com.singularity.todo.core.links

            object TaskLinkSpec {
                const val SCHEME = "singularity://"
                const val HOST = "task"
            }
        """
        assertEquals(0, findings(code).size)
    }

    @Test
    fun `a file named LinkSpec is the home`() {
        val code = """
            package com.singularity.todo.core.links

            val href = "singularity://task/abc"
        """
        assertEquals(0, findings(code, "/repo/shared/src/commonMain/kotlin/TaskLinkSpec.kt").size)
    }

    @Test
    fun `the tests spell the schemes on purpose and are skipped`() {
        val code = """
            package com.singularity.todo.core.links

            val sample = "note://n1"
        """
        assertEquals(
            0,
            findings(code, "/repo/shared/src/commonTest/kotlin/InternalLinksTest.kt").size,
        )
    }

    @Test
    fun `a literal with no scheme is not a finding`() {
        val code = """
            package com.singularity.todo.feature.tasks

            val href = "https://example.com"
            val label = "task"
        """
        assertEquals(0, findings(code).size)
    }
}
