package com.singularity.todo.arch

import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every subprocess in this repository is spawned through `Subprocess`.
 *
 * ## What the rule is protecting
 *
 * `SubprocessTest` runs real commands — real pipes, real exit codes, a real SIGPIPE race
 * — and that is the only subprocess test in the project. It is worth exactly as much as
 * the code it covers. Today `core/process/Subprocess.kt` is the only file allowed to spawn,
 * so it covers all nine call sites; the moment someone reaches for `ProcessBuilder`
 * directly, there is a subprocess in this repository that no test has ever executed.
 *
 * That is not hypothetical. It is how two defects shipped in one feature, three days
 * apart:
 *
 * - `JvmReminderFireCommand.parse` read `args[0]` as a command, so nothing ever matched.
 * - The runner closed the child's pipes right after `start()`, which delivers SIGPIPE and
 *   kills the child with exit **141**. Those exit codes are *capabilities*: 141 read as
 *   "this host cannot display notifications" and "this host cannot schedule anything", so
 *   Desktop reminders refused to arm at all, silently, on every machine.
 *
 * Both were invisible to 2461 passing tests, because both lived in a default that every
 * test replaced with a double. Confining spawning to one file removes that possibility
 * structurally rather than by asking the next person to remember.
 *
 * @see Subprocess
 * @see docs/decisions/2026-10-07-closing-child-streams-sends-sigpipe.md
 */
@Tag("fast")
class SubprocessConfinedTest {

    /**
     * Production sources of `:shared`, derived from `commonMain.root`.
     *
     * Walks up to `<module>/src` so all source sets are covered — a rule that only saw
     * commonMain would miss the file where the bug actually was. Test sources are
     * excluded: they may legitimately construct a process to test it.
     */
    private fun productionSources(): List<File> {
        val root = System.getProperty("commonMain.root")
            ?: error(
                "commonMain.root system property is not set — " +
                    "see the jvmTest task config in shared/build.gradle.kts",
            )
        val sourceRoot = File(root).parentFile?.parentFile
            ?: error("cannot locate the module source root above $root")
        return sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { file -> TEST_SOURCE_MARKERS.any { file.path.contains(it) } }
            .toList()
    }

    @Test
    fun `only Subprocess spawns a process`() {
        val allowed = allowedFiles()
        val offenders = productionSources()
            .filterNot { file -> allowed.any { suffix -> file.path.endsWith(suffix) } }
            .filter { file -> SPAWN_PATTERN.containsMatchIn(file.readText()) }
            .map { it.name }
            .sorted()

        assertEquals(
            emptyList(),
            offenders,
            "spawning a process outside core/process means SubprocessTest does not cover " +
                "it. A SIGPIPE bug in an unwrapped runner reports a capability as " +
                "unavailable and switches the whole feature off without an error — that " +
                "is not a hypothetical, it shipped twice. Use Subprocess.runQuietly, or " +
                "Subprocess.runCapturing when you need the output — offenders: $offenders",
        )
    }

    /**
     * The rule still detects a direct spawn.
     *
     * A scan that stops matching reports success while checking nothing, which is the
     * failure this project has paid for repeatedly. This feeds it the three spellings
     * Kotlin actually offers.
     */
    @Test
    fun `the rule recognises every way to spawn a process`() {
        val samples = listOf(
            "val p = ProcessBuilder(\"notify-send\", \"--version\").start()",
            "val p = ProcessBuilder(listOf(\"at\", \"q\")).redirectErrorStream(true).start()",
            "Runtime.getRuntime().exec(arrayOf(\"secret-tool\", \"lookup\"))",
            "val p = ProcessBuilder(\"/usr/bin/notify-send\").redirectOutput(ProcessBuilder.Redirect.DISCARD).start()",
        )

        for (source in samples) {
            assertTrue(
                SPAWN_PATTERN.containsMatchIn(source),
                "the confinement rule no longer recognises: $source. If a process can be " +
                    "spawned without Subprocess, this gate is decoration",
            )
        }
    }

    /**
     * The confinement is not vacuous.
     *
     * If `Subprocess.kt` were deleted or renamed, every other file would still be scanned
     * and the rule would still pass — because the offenders list is compared against an
     * allowlist, and an allowlist that matches nothing looks exactly like a clean bill of
     * health. This asserts the allowlist names a file that exists and actually spawns.
     */
    @Test
    fun `the allowed file exists and is the one doing the spawning`() {
        val allowed = allowedFiles()
        assertTrue(allowed.isNotEmpty(), "the allowlist must name the file that owns spawning")

        val owner = productionSources().filter { file -> allowed.any { suffix -> file.path.endsWith(suffix) } }
        assertTrue(
            owner.isNotEmpty(),
            "none of the allowed files exist: $allowed. The rule would pass by finding nothing",
        )
        assertTrue(
            owner.any { SPAWN_PATTERN.containsMatchIn(it.readText()) },
            "the allowed file no longer spawns anything, so it is no longer the owner: $allowed",
        )
    }

    private fun allowedFiles(): List<String> = listOf("core/process/Subprocess.kt")

    private companion object {
        /**
         * A spawn, however it is spelled.
         *
         * `Runtime.exec` is included because it is the other way to do this and appears in
         * no current production file — which is exactly the sort of thing that gets
         * introduced once, in a hurry, by someone who did not know about `Subprocess`.
         *
         * `Runtime.addShutdownHook` is deliberately *not* matched. It takes a `Thread`, not
         * a command, and appears in two legitimate places.
         */
        val SPAWN_PATTERN = Regex(
            "ProcessBuilder\\s*\\(|Runtime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*exec\\s*\\(",
        )

        val TEST_SOURCE_MARKERS = listOf("/jvmTest/", "/commonTest/", "/androidUnitTest/", "/androidHostTest/")
    }
}
