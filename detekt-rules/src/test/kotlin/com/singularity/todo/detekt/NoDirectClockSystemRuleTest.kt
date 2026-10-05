package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

class NoDirectClockSystemRuleTest {

    private val rule = NoDirectClockSystemRule(TestConfig())
    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    @Test
    fun `ClockSystem dot call is flagged`() {
        val code = """
            package com.singularity.todo.feature.tasks.data

            class TaskRepository {
                fun now() = Clock.System.now()
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("Direct `Clock.System` is banned"))
    }

    @Test
    fun `ClockSystem now call is flagged`() {
        val code = """
            package com.singularity.todo.core.backup

            fun export(): Instant = Clock.System.now()
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
    }

    @Test
    fun `Clock dot System dot now is flagged`() {
        val code = """
            package com.singularity.todo.core.auth.oauth

            fun tokenExpiry() = Clock.System.now().toEpochMilliseconds()
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(1, findings.size)
    }

    @Test
    fun `injected Clock parameter is not flagged`() {
        // Injected Clock is the recommended pattern — never touches Clock.System directly
        val code = """
            package com.singularity.todo.core.sync

            class SyncEngine(private val clock: Clock) {
                fun now(): Instant = clock.now()
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(0, findings.size)
    }

    @Test
    fun `Clock System without dot call is not flagged`() {
        // Clock type reference without accessing .System
        val code = """
            package com.singularity.todo.core.platform

            fun format(clock: Clock) = clock.toString()
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(0, findings.size)
    }

    @Test
    fun `multiple ClockSystem references in same file are each flagged`() {
        val code = """
            package com.singularity.todo.core.auth.oauth

            class TokenManager {
                fun issuedAt() = Clock.System.now()
                fun expiresAt() = Clock.System.now().plusMillis(3600_000)
            }
        """.trimIndent()
        val ktFile = compileContentForTest(code, "com.example")
        val findings = rule.visitFile(ktFile, languageSettings)
        assertEquals(2, findings.size)
    }
}

// ── The file whitelist ─────────────────────────────────────────────────────────
//
// `isAllowedPath` was extracted as `internal` specifically so the path-based whitelist
// could be unit-tested: `compileContentForTest` derives the file path from the package
// argument and gives no way to point a fixture at a chosen path. It was extracted, and
// then never tested. The same gap made `NoDirectDispatchersRule` and
// `NoStaticProfileAwareCurrentUserRule` no-ops, so the whitelist is now covered directly.

class NoDirectClockSystemWhitelistTest {

    @Test
    fun `Clock platform file is allowed`() {
        assertTrue(
            NoDirectClockSystemRule(TestConfig()).isAllowedPath(
                "/repo/shared/src/commonMain/kotlin/com/singularity/todo/core/platform/Clock.kt",
            ),
        )
    }

    @Test
    fun `CoreDiModule is allowed`() {
        assertTrue(
            NoDirectClockSystemRule(TestConfig()).isAllowedPath(
                "/repo/shared/src/commonMain/kotlin/com/singularity/todo/core/di/CoreDiModule.kt",
            ),
        )
    }

    @Test
    fun `both allowlisted files exist in the tree`() {
        // A whitelist pointing at a file that has been renamed or deleted is a silent
        // hole; assert the targets still exist rather than trusting the string.
        for (suffix in listOf("/core/platform/Clock.kt", "/core/di/CoreDiModule.kt")) {
            val matches = repoFiles().filter { it.endsWith(suffix) }
            assertTrue(
                matches.isNotEmpty(),
                "no file in the repo ends with $suffix — the whitelist entry is stale",
            )
        }
    }

    @Test
    fun `a fake reading the system clock is no longer allowed`() {
        // The `/test/fakes/` exemption existed only while `FakeRepositories.kt`
        // stamped entity timestamps with `Clock.System.now()`. Those 20 reads now
        // go through an injected `Clock` defaulting to `FakeClock`, so nothing
        // under the directory reads the wall clock and the exemption matches
        // nothing. An allowance that matches nothing is indistinguishable from a
        // working check, and it would silently re-open the door the next time a
        // fake reached for `Clock.System` — in a file nobody would think to audit,
        // because fakes are exempt by design in every other project this borrows
        // conventions from.
        assertEquals(
            false,
            NoDirectClockSystemRule(TestConfig()).isAllowedPath(
                "/repo/shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeRepositories.kt",
            ),
        )
    }

    @Test
    fun `no file under the fakes directory reads the system clock`() {
        // The positive control for the entry removed above: the exemption is gone
        // because the reads are gone, not because the check was loosened.
        //
        // This runs the real rule over the real files rather than grepping them
        // for the string "Clock.System". A grep would flag the file's own
        // header comment, which names the call it replaced — and a guard that
        // fails when someone explains themselves is a guard that gets deleted.
        val rule = NoDirectClockSystemRule(TestConfig())
        val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)
        val offenders = repoFiles()
            .filter { it.contains("/test/fakes/") }
            .mapNotNull { path ->
                val source = File(repoRoot(), path)
                if (!source.isFile) return@mapNotNull null
                val ktFile = compileContentForTest(source.readText(), "com.example")
                rule.visitFile(ktFile, languageSettings)
                    .takeIf { it.isNotEmpty() }
                    ?.let { path }
            }
        assertEquals(
            emptyList<String>(),
            offenders,
            "a test double reads the wall clock — pass a `Clock` (default `FakeClock`) instead",
        )
    }

    @Test
    fun `other files are not allowed`() {
        assertEquals(
            false,
            NoDirectClockSystemRule(TestConfig()).isAllowedPath(
                "/repo/shared/src/commonMain/kotlin/com/singularity/todo/core/log/FileLogWriter.kt",
            ),
        )
    }

    @Test
    fun `a file merely containing the allowed name is not allowed`() {
        // endsWith is deliberate: a directory named core/platform/ must not whitelist
        // everything under it.
        assertEquals(
            false,
            NoDirectClockSystemRule(TestConfig()).isAllowedPath(
                "/repo/shared/src/commonMain/kotlin/com/singularity/todo/core/platform/ClockFactory.kt",
            ),
        )
    }

    @Test
    fun `windows paths are handled`() {
        assertTrue(
            NoDirectClockSystemRule(TestConfig()).isAllowedPath(
                "C:\\repo\\shared\\src\\commonMain\\kotlin\\com\\x\\core\\platform\\Clock.kt",
            ),
        )
    }
}

/** Repo-relative paths of every .kt file, used to assert whitelist targets still exist. */
private fun repoRoot(): File {
    var dir = File(".").absoluteFile
    while (!File(dir, "settings.gradle.kts").isFile && dir.parentFile != null) {
        dir = dir.parentFile
    }
    return dir
}

private fun repoFiles(): List<String> {
    val root = repoRoot()
    return root.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".kt") }
        .map { it.relativeTo(root).invariantSeparatorsPath }
        .toList()
}
