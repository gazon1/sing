package com.singularity.todo.feature.reminders

import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The launcher path a Desktop reminder unit executes, pinned to what packaging produces.
 *
 * ## What was broken
 *
 * `/usr/bin/singularity-todo` was written as a constant in
 * `JvmReminderScheduler`, and `packageName = "singularity-todo"` was written in
 * `desktopApp/build.gradle.kts`. Two files in two Gradle modules, with **zero** references
 * between them and no test on either side.
 *
 * The consequence is a silent, total failure: rename the package, publish a new Deb, and
 * every `systemd-run --user` invocation exits non-zero because the `ExecStart` path does
 * not exist. No reminder fires on any machine. The only evidence is an exit code inside a
 * transient unit that systemd has already discarded — and the user sees an 18:00 reminder
 * that simply never arrives.
 *
 * Nothing in the type system can catch this, so it is caught here instead. `schedule`
 * throws when `systemd-run` fails, so the failure is at least loud *after* installation;
 * this test moves it to *before*.
 *
 * ## Why it reads the build script
 *
 * The alternative — deriving the path from `packageName` at runtime — cannot be done,
 * because `packageName` is packaging metadata that vanishes once a Deb exists. Asserting
 * the two agree at build time is the only moment at which both are available.
 */
@Tag("fast")
class JvmReminderSchedulerLauncherPathTest {

    /**
     * Reads the `packageName` jpackage will produce.
     *
     * A deliberately narrow regex rather than a Gradle parse: it needs the one value, and
     * a build-script parse would break on every unrelated Gradle syntax change while
     * adding nothing to what is being checked.
     */
    private fun packagedName(): String {
        val script = System.getProperty("desktopApp.buildScript")
            ?: error(
                "desktopApp.buildScript system property is not set — " +
                    "see the jvmTest task config in shared/build.gradle.kts",
            )
        val file = File(script)
        assertTrue(file.isFile, "the packaging script $script does not exist, so this test is measuring nothing")
        return PACKAGE_NAME.find(file.readText())?.groupValues?.get(1)
            ?: error("no packageName found in $script — if jpackage's config moved, fix this test")
    }

    @Test
    fun `the reminder launcher path matches what the Deb actually installs`() {
        val expected = "/usr/bin/${packagedName()}"

        assertEquals(
            expected,
            REMINDER_LAUNCHER_PATH,
            "a reminder unit runs this exact path. If the package was renamed, update " +
                "REMINDER_LAUNCHER_PATH — or every Desktop reminder silently stops firing " +
                "on every installed machine.",
        )
    }

    /**
     * Guards the test itself.
     *
     * Without this, `packageName` could be deleted from the build script, the regex could
     * quietly stop matching, and the test above would go on comparing a constant to a
     * constant and passing. A gate that measures nothing looks exactly like a gate that
     * measures something and finds nothing wrong.
     */
    @Test
    fun `the packaging script still declares a package name`() {
        val name = packagedName()

        assertTrue(
            name.isNotBlank(),
            "packageName must not be blank: a blank name produces a launcher nothing can invoke",
        )
    }

    private companion object {
        val PACKAGE_NAME = Regex("""packageName\s*=\s*"([^"]+)"""")
    }
}
