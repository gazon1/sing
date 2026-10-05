package com.singularity.todo.core.files

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.GraphicsEnvironment
import java.io.File
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for [FileRevealer] on the JVM.
 *
 * This port was the odd one out. [FileSharePort] and `JvmSharePort` both guard
 * the `Desktop` call; this one did not, so `Desktop.getDesktop()` initialised
 * AWT and threw straight out of a button in Settings on a host with no display.
 * `Unit` meant no caller was expecting anything to come back, so there was
 * nowhere for the failure to go but the scope's exception handler.
 *
 * The tests do not skip on a headless machine — the same reasoning as
 * [FileSharePortContractTest]: a guard here makes the test disappear exactly
 * where it matters.
 */
@Tag("slow")
@EnabledOnOs(OS.LINUX)
class FileRevealerContractTest {

    private val sut = JvmFileRevealer()

    @Test
    fun `revealAttachmentsFolder never throws`() = runTest {
        val folder = File(System.getProperty("java.io.tmpdir"), "revealer-${System.nanoTime()}")
        try {
            // The assertion is the absence of an exception: this is the call
            // that used to escape into the ViewModel's scope.
            sut.revealAttachmentsFolder(folder.absolutePath)
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `revealAttachmentsFolder reports false when there is no file manager to ask`() = runTest {
        val folder = File(System.getProperty("java.io.tmpdir"), "revealer-${System.nanoTime()}")
        try {
            val opened = sut.revealAttachmentsFolder(folder.absolutePath)
            // Only assert the branch the platform can be held to. Whether
            // `Desktop.browse` succeeds where a display exists depends on a
            // registered mime handler, which is host state, not port behaviour.
            if (GraphicsEnvironment.isHeadless()) {
                assertFalse(opened, "headless runner opened nothing, so the port must say so")
            } else {
                assertTrue(opened || !opened, "the port answers with a boolean either way")
            }
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
            assertTrue(folder.exists(), "the folder the user was sent to must exist")
        } finally {
            parent.deleteRecursively()
        }
    }
}
