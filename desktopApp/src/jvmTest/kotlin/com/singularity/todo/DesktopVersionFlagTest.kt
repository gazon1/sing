package com.singularity.todo

import com.singularity.todo.core.version.appVersion
import org.junit.jupiter.api.Tag
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * `--version` is what the release pipeline's install-and-smoke step runs against the
 * *installed* launcher (#664). Until it existed, the only way to exercise a packaged
 * desktop build was to open a window — impossible in CI, and impossible on a machine
 * that is not a desktop at all.
 *
 * The interesting property is not that the flag prints something. It is that the flag
 * returns before anything else in [main]: no Koin, no database, no AWT. A `--version`
 * that had to start the app would be useless as a smoke test, because its failure mode
 * would be indistinguishable from the environment having no display.
 */
@Tag("fast")
class DesktopVersionFlagTest {

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8)
    }

    @Test
    fun `version flag prints the running version and returns`() {
        val name = System.getProperty("singularity.version") ?: appVersion().name
        val out = captureStdout { main(arrayOf("--version")) }
        assertContains(out, "singularity-todo")
        assertContains(out, name)
    }

    @Test
    fun `version flag prints the version code, not only the name`() {
        val out = captureStdout { main(arrayOf("--version")) }
        assertContains(out, "(${appVersion().code})")
    }

    @Test
    fun `version flag is recognised wherever it appears in argv`() {
        // jpackage's launcher appends its own arguments, and a user may type them in
        // either order. Searching rather than reading `args[0]` is the same decision
        // JvmReminderFireCommand.parse documents: `args[0]` is the path the OS used to
        // launch the process, not a command.
        val out = captureStdout { main(arrayOf("/opt/singularity-todo/bin/singularity-todo", "--version")) }
        assertContains(out, "singularity-todo")
        assertTrue(out.isNotBlank())
    }
}