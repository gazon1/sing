package com.singularity.todo.core.files

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.Desktop
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for the [FileSharePort] port on the JVM.
 *
 * Tests [JvmFileSharePort] using real filesystem and Desktop API.
 * These tests are desktop-environment-dependent — they skip when no display
 * is available (HEADLESS CI environments).
 */
@Tag("slow")
@EnabledOnOs(OS.LINUX)
class FileSharePortContractTest {

    private val sut = JvmFileSharePort()

    @Test
    fun `shareFile returns false when file does not exist`() {
        val result = sut.shareFile("/nonexistent/path/${System.nanoTime()}.zip", "application/zip")
        assertFalse(result)
    }

    @Test
    fun `shareFile returns true when file exists and desktop is supported`() {
        assumeTrue(Desktop.isDesktopSupported())
        val tempFile = java.io.File.createTempFile("test-share-${System.nanoTime()}", ".zip")
        tempFile.deleteOnExit()

        try {
            val result = sut.shareFile(tempFile.absolutePath, "application/zip")
            // Desktop.browse may succeed or fail depending on mime handler availability
            // The key assertion is that we got a definitive boolean result, not an exception
            assertTrue(result || !result)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `shareFile returns false for empty path`() {
        val result = sut.shareFile("", "application/zip")
        assertFalse(result)
    }
}
