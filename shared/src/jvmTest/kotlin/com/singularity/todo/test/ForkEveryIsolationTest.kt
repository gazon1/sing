package com.singularity.todo.test

import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `forkEvery = 1` is not only about Koin globals.
 *
 * `BackgroundFailureHandler` is process-wide mutable state: `install { … }` replaces a
 * target that every background scope in the JVM reads per failure. `forkEvery = 1` gives
 * each test class its own JVM, which is what stops one class's target from receiving a
 * failure raised in a class running at the same moment. The build file states that as an
 * incidental consequence of isolating Koin; it is also a precondition for a test's
 * correctness, and an unstated precondition is one that a future change to the test task
 * silently removes.
 *
 * The second half is the same dependency one level down. JUnit runs test *methods* within
 * a class concurrently, so a class that installs a target has to pin itself to
 * `SAME_THREAD` or two of its own methods race. That is a convention, and a convention
 * needs a check.
 */
@Tag("fast")
class ForkEveryIsolationTest {

    @Test
    fun `jvmTest forks a fresh JVM per class, which is what makes a process-wide target safe`() {
        val configured = System.getProperty("jvmTest.forkEvery")
            ?: error(
                "jvmTest.forkEvery is not published as a system property — " +
                    "see the jvmTest block in shared/build.gradle.kts",
            )

        assertEquals(
            "1",
            configured,
            "The background-failure handler test is only correct because no other test class " +
                "shares its JVM. Without per-class forking, its installed target can capture " +
                "a failure from a class running concurrently, and that failure is reported " +
                "against the wrong test — or not at all.",
        )
    }

    @Test
    fun `every test class that installs a background failure target runs its methods in sequence`() {
        val offenders = testFiles()
            .filter { "BackgroundFailureHandler.install" in it.code }
            .filterNot { "@Execution(" in it.code }
            .map { it.path.name }

        assertTrue(
            offenders.isEmpty(),
            "These classes install a process-wide failure target but do not pin their " +
                "execution mode. JUnit runs methods within a class concurrently, so two of " +
                "their own methods can race on the same target:\n  " +
                offenders.joinToString("\n  ") +
                "\nAdd @Execution(ExecutionMode.SAME_THREAD).",
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

    /** Comments are not code: a KDoc that *mentions* the call must not count as one. */
    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")
}
