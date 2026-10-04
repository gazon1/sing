package com.singularity.todo.core.files

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
 * Tests [JvmFileSharePort] against the real filesystem and the real Desktop API.
 *
 * These tests do **not** skip on a headless machine. The previous version guarded
 * its second case with `assumeTrue(Desktop.isDesktopSupported())`, which meant CI —
 * the only place that runs them every time — executed nothing, and the skipped
 * count it produced was the thing that finally made it visible. A guard here does
 * not make a test portable; it makes it disappear exactly where it matters.
 *
 * The two assertions that survive a headless runner are the ones the port can
 * actually promise: a definitive boolean, never an exception, and `false` when
 * there is no browser to hand the file to. Whether `Desktop.browse` succeeds on a
 * machine *with* a display depends on a registered mime handler, which is host
 * state rather than port behaviour, so that case is left unasserted instead of
 * being asserted tautologically.
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
    fun `shareFile never throws and reports failure when no browser is available`() {
        val tempFile = java.io.File.createTempFile("test-share-${System.nanoTime()}", ".zip")
        tempFile.deleteOnExit()

        try {
            val outcome = runCatching { sut.shareFile(tempFile.absolutePath, "application/zip") }

            assertTrue(
                outcome.isSuccess,
                "shareFile propagated an exception instead of reporting failure: " +
                    "${outcome.exceptionOrNull()}",
            )

            if (!Desktop.isDesktopSupported()) {
                // Deterministic on CI, where there is no display at all.
                assertFalse(
                    outcome.getOrThrow(),
                    "headless runner has no Desktop.browse, so the port must report false",
                )
            }
        } finally {
            tempFile.delete()
        }
    }
}
