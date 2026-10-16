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
 * ## What this test was wrong about
 *
 * It asserted `REMINDER_LAUNCHER_PATH == "/usr/bin/${packagedName()}"`, on the stated
 * premise that "jpackage puts a Deb's launcher on `PATH` under its `packageName`".
 * That premise is false. Both halves of the assertion came from the same belief, so it
 * was a constant compared with a constant: it would have kept passing against the exact
 * bug it existed to prevent, and its docstring claimed it "matches what the Deb actually
 * installs" while never reading a Deb.
 *
 * The real layout, read out of a published package on 2026-10-16:
 *
 * ```
 * $ dpkg-deb -c singularity-0.2.1-rc.1-linux-x64.deb | grep bin/singularity
 * -rwxr-xr-x root/root 17688 ./opt/singularity-todo/bin/singularity-todo
 * $ rpm -qlp singularity-0.2.1-rc.1-linux-x64.rpm | grep bin/singularity
 * /opt/singularity-todo/bin/singularity-todo
 * ```
 *
 * jpackage's default `installationPath` is `/opt/<packageName>`, and it creates no
 * `/usr/bin` symlink. The expectation below is now that path, and the release pipeline's
 * install-and-smoke step installs the package and executes the launcher before anything
 * is published (#664) — so the claim this file makes is also checked against a real
 * artifact, rather than only against another constant.
 *
 * ## Why it reads the build script
 *
 * The alternative — deriving the path at runtime — cannot be done: `packageName` is
 * packaging metadata that vanishes once a Deb exists. Asserting the two agree at build
 * time is the only moment at which both are available. It is still worth doing: the
 * *premise* is now one a package can falsify, where before it was one only the build
 * script agreed with.
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
        // jpackage's default installationPath for a Linux package is `/opt/<packageName>`,
        // and it does not symlink the launcher onto PATH. Verified against both published
        // formats on 2026-10-16; the release pipeline re-checks it on every real package.
        val expected = "/opt/${packagedName()}/bin/${packagedName()}"

        assertEquals(
            expected,
            REMINDER_LAUNCHER_PATH,
            "a reminder unit runs this exact path. If the package was renamed, update " +
                "REMINDER_LAUNCHER_PATH — or every Desktop reminder silently stops firing " +
                "on every installed machine.",
        )
    }

    /**
     * The failure this test previously could not see, stated as an assertion.
     *
     * `/usr/bin/<packageName>` is the path people expect and the path nothing installs.
     * If this ever passes again, the premise above has been reintroduced and every
     * reminder is broken again.
     */
    @Test
    fun `the launcher path is not the PATH location jpackage never creates`() {
        val notInstalled = "/usr/bin/${packagedName()}"

        assertTrue(
            !REMINDER_LAUNCHER_PATH.startsWith(notInstalled),
            "REMINDER_LAUNCHER_PATH is $REMINDER_LAUNCHER_PATH, which is the /usr/bin location " +
                "that no published package creates. This is the bug the test above used to " +
                "assert was correct.",
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
