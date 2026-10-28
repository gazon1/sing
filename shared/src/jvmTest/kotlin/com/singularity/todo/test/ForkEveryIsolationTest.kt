package com.singularity.todo.test

import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `forkEvery = 1` is a build setting that other things silently depend on.
 *
 * ## It used to be load-bearing for the failure handler, and no longer is
 *
 * `BackgroundFailureHandler` was process-wide mutable state: `install { … }` replaced a target
 * that every background scope in the JVM read per failure. A test of that global was only
 * correct because each test class had its own JVM — and because the class itself pinned
 * `@Execution(SAME_THREAD)`, because JUnit runs methods within a class concurrently. Two
 * preconditions for one test's correctness, neither of them visible at the call site. The
 * build file stated `forkEvery = 1` as an incidental way to isolate Koin globals; it was also
 * a precondition, and an unstated precondition is one a future change to the test task
 * removes without anyone noticing.
 *
 * The handler is a value now, so that whole hazard is gone: a test builds its own handler and
 * nothing running beside it can capture its failures. This test no longer exists to protect
 * the handler.
 *
 * ## What it protects now
 *
 * Koin's graph is still process-wide state in every test, and `forkEvery = 1` is still what
 * makes a Koin-global assertion in one class independent of another. That dependence is real
 * and equally invisible, so the setting stays asserted. What is *not* asserted any more is
 * the `@Execution` pin, because there is no global left to race on — a convention with nothing
 * behind it is just a line to keep deleting.
 */
@Tag("fast")
class ForkEveryIsolationTest {

    @Test
    fun `jvmTest does not fork a JVM per class`() {
        val configured = System.getProperty("jvmTest.forkEvery")
            ?: error(
                "jvmTest.forkEvery is not published as a system property — " +
                    "see the jvmTest block in shared/build.gradle.kts",
            )

        assertEquals(
            "0",
            configured,
            "Per-class forking is off; Koin global state is cleaned up per class by " +
                "KoinIsolationExtension, so forkEvery=1 is no longer load-bearing.",
        )
    }

    @Test
    fun `KoinIsolationExtension is registered as a global JUnit extension`() {
        val commonMain = System.getProperty("commonMain.root")
            ?: error("commonMain.root system property is not set")
        val moduleDir = Path.of(commonMain).toAbsolutePath().normalize().parent?.parent?.parent
            ?: error("Could not derive module directory")
        val servicesFile = moduleDir.resolve(
            "src/jvmTest/resources/META-INF/services/org.junit.jupiter.api.extension.Extension",
        )
        assertTrue(servicesFile.exists(), "JUnit extension service file is missing")
        assertTrue(
            servicesFile.readText().contains("KoinIsolationExtension"),
            "KoinIsolationExtension must be registered globally",
        )
    }

    private data class TestFile(val path: Path, val code: String)

    /**
     * The test JVM's working directory is not the project directory, which is why the
     * build publishes `commonMain.root`. Derive the test roots from it rather than adding
     * a second property for the same problem: that value ends with
     * `src/commonMain/kotlin`, so the module directory is three levels up.
     */
    private fun testFiles(): List<TestFile> {
        val commonMain = System.getProperty("commonMain.root")
            ?: error(
                "commonMain.root system property is not set — " +
                    "see the jvmTest block in shared/build.gradle.kts",
            )
        val moduleDir = Path.of(commonMain).toAbsolutePath().normalize().parent?.parent?.parent
            ?: error("Could not derive the module directory from commonMain.root=$commonMain")

        return listOf("src/commonTest", "src/jvmTest")
            .map { moduleDir.resolve(it) }
            .filter { it.exists() }
            .flatMap { root ->
                Files.walk(root).use { stream ->
                    stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                        .map { TestFile(it, stripComments(it.readText())) }
                        .toList()
                }
            }
    }

    /** Comments are not code: a KDoc that *mentions* a call must not count as one. */
    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")
}
