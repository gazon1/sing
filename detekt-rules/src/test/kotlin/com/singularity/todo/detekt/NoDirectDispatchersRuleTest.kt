package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [NoDirectDispatchersRule] and [NoDirectDispatchersPolicy].
 *
 * ## Why this test file exists
 *
 * `Dispatchers_IO is flagged` and `Dispatchers_Default is flagged` failed for the whole
 * life of the rule, and nobody noticed, because no CI job ran `:detekt-rules:test`. The
 * rule could not fire at all: it required the dot-qualified selector to be a
 * `KtCallExpression`, but in `Dispatchers.IO` the selector is a
 * `KtNameReferenceExpression` (`IO` is a property, not a call), and in
 * `Dispatchers.IO.limitedParallelism(1)` the receiver is itself dot-qualified. Both
 * shapes returned early, so the rule reported nothing for any input.
 *
 * The tests were right; the rule was wrong.
 *
 * ## Why the path cases are tested against the policy, not the PSI
 *
 * `compileContentForTest(content, Path)` **discards the directory** of the path you pass
 * it — `virtualFilePath` comes back as `/X.kt` for any input. A path-scoped rule
 * therefore cannot be exercised through the PSI layer. `NoDirectClockSystemRule` has the
 * same limitation and its `FileLogWriter` whitelist has no test for exactly this reason.
 */
class NoDirectDispatchersRuleTest {

    private val rule = NoDirectDispatchersRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    private fun findingsIn(code: String) =
        rule.visitFile(compileContentForTest(code.trimIndent(), "com.example"), languageSettings)

    // ── PSI wiring: does the rule reach these expressions at all? ──────────────────

    @Test
    fun `bare Dispatchers_IO is flagged`() {
        val code = """
            package com.example

            fun writeLog() {
                withContext(Dispatchers.IO) { }
            }
        """
        val findings = findingsIn(code)
        assertEquals(1, findings.size, "expected one finding, got $findings")
        assertTrue(findings[0].message.contains("Dispatchers.IO"))
    }

    @Test
    fun `bare Dispatchers_Default is flagged`() {
        val code = """
            package com.example

            fun compute() {
                withContext(Dispatchers.Default) { }
            }
        """
        val findings = findingsIn(code)
        assertEquals(1, findings.size, "expected one finding, got $findings")
        assertTrue(findings[0].message.contains("Dispatchers.Default"))
    }

    @Test
    fun `bare Dispatchers_Main is flagged`() {
        val code = """
            package com.example

            fun render() {
                withContext(Dispatchers.Main) { }
            }
        """
        val findings = findingsIn(code)
        assertEquals(1, findings.size, "expected one finding, got $findings")
        assertTrue(findings[0].message.contains("Dispatchers.Main"))
    }

    /**
     * The regression that matters most. Every other PSI test is a filtering rule, and a
     * filter that never matches anything passes all of them.
     */
    @Test
    fun `rule is not a no-op`() {
        val code = """
            package com.example

            fun compute() {
                withContext(Dispatchers.Default) { }
            }
        """
        assertTrue(
            findingsIn(code).isNotEmpty(),
            "NoDirectDispatchersRule reported nothing for a bare Dispatchers.Default — " +
                "in `Dispatchers.IO` the selector is a KtNameReferenceExpression, " +
                "not a KtCallExpression",
        )
    }

    @Test
    fun `limitedParallelism wrapper is not flagged`() {
        val code = """
            package com.example

            private val Bounded = Dispatchers.IO.limitedParallelism(1)
        """
        assertEquals(0, findingsIn(code).size, "limitedParallelism(n) is a safe wrapper")
    }

    @Test
    fun `unrelated qualified expressions are not flagged`() {
        val code = """
            package com.example

            val scope = CoroutineScope(SupervisorJob() + other.IO)
            val plain = Dispatchers.Unconfined
        """
        assertEquals(0, findingsIn(code).size)
    }

    // ── Policy: the path-scoped branches, unreachable through the PSI layer ─────────

    private fun violation(
        path: String,
        selector: String = "IO",
        limitedWrapper: Boolean = false,
    ) = NoDirectDispatchersPolicy.violation(
        filePath = path,
        receiver = "Dispatchers",
        selector = selector,
        limitedWrapper = limitedWrapper,
    )

    @Test
    fun `commonMain production is flagged`() {
        val path = "/repo/shared/src/commonMain/kotlin/com/singularity/todo/core/x/X.kt"
        assertNotNull(violation(path), "commonMain is the rule's scope")
    }

    @Test
    fun `FileLogWriter is whitelisted`() {
        val path = "/repo/shared/src/commonMain/kotlin/com/singularity/todo/core/log/FileLogWriter.kt"
        assertNull(violation(path), "FileLogWriter's single-threaded IO is intentional")
    }

    @Test
    fun `FileLogWriter whitelist does not leak to other files`() {
        val path = "/repo/shared/src/commonMain/kotlin/com/singularity/todo/core/log/OtherWriter.kt"
        assertNotNull(violation(path), "the whitelist is by exact file, not by directory")
    }

    @Test
    fun `platform source sets are out of scope`() {
        // Verified against the tree 2026-10-05: jvmMain has 8 such sites and androidMain
        // 11, all inside port implementations. Banning them would ban the ports from
        // implementing their ports.
        for (sourceSet in listOf("jvmMain", "androidMain", "iosMain")) {
            val path = "/repo/shared/src/$sourceSet/kotlin/com/singularity/todo/core/x/X.kt"
            assertNull(violation(path), "$sourceSet chooses its own dispatcher by design")
        }
    }

    @Test
    fun `test source sets are out of scope`() {
        for (sourceSet in listOf("commonTest", "jvmTest", "androidTest")) {
            val path = "/repo/shared/src/$sourceSet/kotlin/com/singularity/todo/core/x/X.kt"
            assertNull(violation(path), "$sourceSet is not production")
        }
    }

    @Test
    fun `preview and test subdirectories are out of scope`() {
        // The repo's real convention is a `preview/` package (core/ui/preview), not a
        // `Preview.kt` filename — there are 1 preview directories and 0 Preview.kt files.
        assertNull(violation("/repo/shared/src/commonMain/kotlin/com/x/ui/preview/Box.kt"))
        assertNull(violation("/repo/shared/src/commonMain/kotlin/com/x/test/Foo.kt"))
    }

    @Test
    fun `a Preview filename outside a preview package is still flagged`() {
        // The exclusion is by directory, matching what the repo actually does. A
        // `Preview.kt` in a normal package is ordinary production code.
        assertNotNull(violation("/repo/shared/src/commonMain/kotlin/com/x/ui/Preview.kt"))
    }

    @Test
    fun `windows paths are handled`() {
        val path = "C:\\repo\\shared\\src\\jvmMain\\kotlin\\com\\x\\X.kt"
        assertNull(violation(path), "backslashes must not defeat the source-set check")
    }

    @Test
    fun `unrecognised path fails closed`() {
        // Better to demand an explicit whitelist entry than to silently disable the ban
        // because a path shape was not anticipated.
        assertNotNull(violation("/X.kt"), "unknown path must be treated as commonMain")
    }

    @Test
    fun `only the three banned dispatchers are reported`() {
        val path = "/repo/shared/src/commonMain/kotlin/com/x/X.kt"
        for (banned in listOf("IO", "Default", "Main")) {
            assertNotNull(violation(path, selector = banned), "$banned must be reported")
        }
        for (allowed in listOf("Unconfined", "IO_TEST", "Defaultish")) {
            assertNull(violation(path, selector = allowed), "$allowed must not be reported")
        }
    }

    @Test
    fun `non-Dispatchers receivers are never reported`() {
        val path = "/repo/shared/src/commonMain/kotlin/com/x/X.kt"
        assertNull(
            NoDirectDispatchersPolicy.violation(path, "Other", "IO", false),
            "the receiver must literally be Dispatchers",
        )
        assertNull(
            NoDirectDispatchersPolicy.violation(path, null, "IO", false),
            "a null receiver means the PSI shape is not what we think it is",
        )
    }
}
