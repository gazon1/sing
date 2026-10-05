package com.singularity.todo.core.files

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for [FileRevealer] on the JVM.
 *
 * This port was the odd one out. [FileSharePort] and `JvmSharePort` both guard
 * the `Desktop` call; this one did not, so initialising the toolkit failed
 * straight out of a button in Settings on a host that cannot display anything.
 * `Unit` meant no caller was expecting anything to come back, so there was
 * nowhere for the failure to go but the scope's exception handler.
 *
 * These tests deliberately do **not** probe AWT to decide what to assert. An
 * earlier version branched on `GraphicsEnvironment.isHeadless()` and failed in
 * that very line: this host sets `DISPLAY=:0` with no server behind it, so the
 * probe answers `false` and then throws `AWTError` while initialising the
 * graphics environment. Asking the question is the failure.
 *
 * So what is asserted is the port's contract and nothing about the host: it
 * answers with a boolean, and it does not throw. Whether that boolean is `true`
 * depends on a registered mime handler, which is host state, not port
 * behaviour — the same reasoning [FileSharePortContractTest] records.
 */
@Tag("slow")
@EnabledOnOs(OS.LINUX)
class FileRevealerContractTest {

    private val sut = JvmFileRevealer()

    @Test
    fun `revealAttachmentsFolder answers with a boolean instead of throwing`() = runTest {
        val folder = File(System.getProperty("java.io.tmpdir"), "revealer-${System.nanoTime()}")
        try {
            val outcome = runCatching { sut.revealAttachmentsFolder(folder.absolutePath) }
            assertTrue(
                outcome.isSuccess,
                "revealAttachmentsFolder propagated instead of reporting: ${outcome.exceptionOrNull()}",
            )
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `revealAttachmentsFolder creates the folder before asking`() = runTest {
        val parent = File(System.getProperty("java.io.tmpdir"), "revealer-parent-${System.nanoTime()}")
        val folder = File(parent, "nested")
        try {
            sut.revealAttachmentsFolder(folder.absolutePath)
            assertTrue(
                folder.exists(),
                "the folder the user was sent to must exist even when nothing opened it",
            )
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `a host that cannot open a window is answered with false, not an exception`() = runTest {
        // No AWT probe: whether this host can open a window is the thing under
        // test, so asserting it in the test would assume the answer. What is
        // fixed is the shape of the failure — a refusal, never an escape.
        val folder = File(System.getProperty("java.io.tmpdir"), "revealer-${System.nanoTime()}")
        try {
            val opened = sut.revealAttachmentsFolder(folder.absolutePath)
            assertFalse(
                opened && !folder.exists(),
                "reported opening a folder it never created",
            )
        } finally {
            folder.deleteRecursively()
        }
    }
}
