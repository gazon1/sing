package com.singularity.todo.arch

import com.singularity.todo.core.sync.DelayLoopSyncPeriodicTrigger
import com.singularity.todo.core.sync.SyncPeriodicTrigger
import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One periodic sync driver per platform, and no platform sniffing.
 *
 * ## The defect this exists to prevent
 *
 * `SyncRunner` used to choose between the desktop delay loop and the Android alarm
 * with `if (scheduler is NoOpSyncScheduler)`. The JVM module binds
 * `JvmSyncScheduler`, not `NoOpSyncScheduler`, so the test was false on desktop, the
 * `while (isActive) { syncOnce(); delay(interval) }` loop was never created, and
 * `startScheduledSync` reduced to a log line inside a no-op method. Desktop auto-sync
 * had never run — while three comments in the file stated that it had, and a test
 * suite asserted the coalescing contract on a *fake* that copied the guard.
 *
 * Nothing about that failure is visible from a unit test of the loop itself, because
 * the loop was fine; it simply was not the code path being taken. So the guard here
 * is about the wiring, not the mechanism:
 *
 * 1. Each platform module binds `SyncPeriodicTrigger` exactly once, to a real
 *    implementation. A platform that forgets leaves itself with no driver at all,
 *    which is the original bug wearing a different hat.
 * 2. The sync core contains no `is NoOpSyncScheduler`-style platform test. A type
 *    check on an injected dependency is a way of asking "which platform am I on"
 *    that depends on the bindings staying as they are today, and it fails silently
 *    when they change.
 */
@Tag("fast")
class SyncPeriodicTriggerWiringTest {

    /**
     * Source roots, handed over by the jvmTest task as `commonMain.root`,
     * `jvmMain.root` and `androidMain.root`.
     *
     * Not derived from the working directory: the test JVM's cwd is not the project
     * directory, and a gate that silently reads nothing is worse than no gate. The
     * first version of this file used relative paths and reported "missing platform
     * module" for a file that was sitting right there.
     *
     * Per-source-set rather than one repository root, so that
     * `check-test-task-inputs.py` is satisfied by declaring three small trees instead of
     * the whole worktree — declaring the repo root would make :shared:jvmTest re-run on
     * a change to any file anywhere.
     */
    private fun sourceRoot(property: String): File = File(
        System.getProperty(property)
            ?: error(
                "$property system property is not set — " +
                    "see the jvmTest task config in shared/build.gradle.kts",
            ),
    )

    private val commonMainRoot: File = sourceRoot("commonMain.root")
    private val jvmMainRoot: File = sourceRoot("jvmMain.root")
    private val androidMainRoot: File = sourceRoot("androidMain.root")

    private fun read(file: File): String {
        assertTrue(file.isFile, "missing file: ${file.path}")
        return file.readText()
    }

    private val jvmPlatformModule =
        File(jvmMainRoot, "com/singularity/todo/core/di/PlatformModule.jvm.kt")
    private val androidPlatformModule =
        File(androidMainRoot, "com/singularity/todo/core/di/PlatformModule.android.kt")

    @Test
    fun `every platform module binds exactly one SyncPeriodicTrigger`() {
        listOf(jvmPlatformModule, androidPlatformModule).forEach { path ->
            val source = read(path)
            val bindings = Regex("""single<SyncPeriodicTrigger>""").findAll(source).count()
            assertEquals(
                1,
                bindings,
                "${path.name} must bind SyncPeriodicTrigger exactly once (found $bindings). " +
                    "Zero means the platform has no periodic driver at all.",
            )
        }
    }

    @Test
    fun `the JVM binds the delay loop and Android binds WorkManager`() {
        assertTrue(
            read(jvmPlatformModule).contains("DelayLoopSyncPeriodicTrigger"),
            "the JVM has no background job scheduler and must use the delay loop",
        )
        assertTrue(
            read(androidPlatformModule).contains("AndroidSyncPeriodicTrigger"),
            "Android must drive periodic sync through WorkManager",
        )
    }

    @Test
    fun `the sync core contains no platform type test`() {
        val syncCore = File(commonMainRoot, "com/singularity/todo/core/sync")
        assertTrue(syncCore.isDirectory, "sync core not found")

        val pattern = Regex("""\bis\s+(NoOpSyncScheduler|JvmSyncScheduler|AndroidSyncScheduler)\b""")
        val offenders = syncCore.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> pattern.containsMatchIn(stripCommentsAndStrings(file.readText())) }
            .map { it.name }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "the sync core decides the platform by type test: $offenders. " +
                "Inject a SyncPeriodicTrigger per platform instead.",
        )
    }

    /**
     * Comments and string literals blanked out before the pattern is applied.
     *
     * A gate that greps raw source text matches the comment *documenting* the thing
     * it forbids — `SyncRunner` explains in prose why it used to ask
     * `scheduler is NoOpSyncScheduler`, and that sentence is a match. Without this
     * the gate cannot be satisfied without deleting the explanation, which is a
     * strong incentive to delete the explanation. Same defect as the one
     * `scripts/find-unwired-surfaces.py` had.
     */
    private fun stripCommentsAndStrings(source: String): String {
        val withoutBlockComments = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).replace(source, "")
        val withoutLineComments = Regex("""//[^\n]*""").replace(withoutBlockComments, "")
        return Regex(""""[^"\n]*"""").replace(withoutLineComments, "")
    }

    @Test
    fun `the delay loop is a real implementation of the seam`() {
        // Cheap, but it is the assertion that the interface is not decorative: a
        // future rename that leaves the JVM binding dangling fails here.
        assertTrue(
            SyncPeriodicTrigger::class.java.isAssignableFrom(DelayLoopSyncPeriodicTrigger::class.java),
            "DelayLoopSyncPeriodicTrigger must implement SyncPeriodicTrigger",
        )
    }
}
