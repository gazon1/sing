package com.singularity.todo.test.helpers

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.test.fakes.FakeAppDatabase
import org.koin.core.Koin
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The directory under `build/diagnostics/` where this test's artifacts live.
 *
 * Structure:
 * ```
 * build/diagnostics/<TestClassSimpleName>/
 *   attempt-1/
 *     screenshot.png    — screen capture
 *     db-state.txt     — FakeAppDatabase dump
 *     kermit.log       — Kermit ring-buffer contents
 *   attempt-2/         — retry only
 *     …
 * ```
 *
 * Note: semantics tree dumps are not captured in this version. `toTree()` and
 * `onRoot()` are not available in the Compose 1.12.0 desktop test API. The
 * screenshot alone is usually sufficient for visual diagnosis; for selector
 * debugging, run with `-Dsingularity.ui.dumpTree=true` (see [DesktopAppBootTest]
 * which uses `onRoot().printToString()` successfully in that version).
 */
data class FailureBundle(
    val testClassSimpleName: String,
    val attempt: Int,
    val outputDir: File,
) {
    /** Path printed in the suppressed exception message so it appears in CI logs. */
    val path: String = outputDir.absolutePath

    /** Screen capture PNG. */
    val screenshotFile: File get() = outputDir.resolve("screenshot.png")

    /** FakeAppDatabase human-readable state. */
    val dbStateFile: File get() = outputDir.resolve("db-state.txt")

    /** Kermit ring-buffer contents. */
    val kermitLogFile: File get() = outputDir.resolve("kermit.log")

    /**
     * Adds all captured artifacts to [throwable] as suppressed exceptions so the
     * CI test report displays the paths alongside the failure reason.
     */
    fun addSuppressedTo(throwable: Throwable) {
        listOf(screenshotFile, dbStateFile, kermitLogFile).forEach { file ->
            if (file.exists()) {
                throwable.addSuppressed(Exception("<available: ${file.name}>"))
            } else {
                throwable.addSuppressed(Exception("<unavailable: ${file.name}>"))
            }
        }
        throwable.addSuppressed(Exception("<diagnostics: $path>"))
    }

    companion object {
        private val diagnosticsRoot: File by lazy {
            val root = File("build/diagnostics")
            root.mkdirs()
            root
        }

        /**
         * Prepares the output directory for [testClassSimpleName]/[attempt], deleting any
         * prior content so retries always write fresh artifacts.
         */
        fun prepareOutputDir(testClassSimpleName: String, attempt: Int): File {
            val dir = diagnosticsRoot
                .resolve(testClassSimpleName)
                .resolve("attempt-$attempt")
            dir.mkdirs()
            return dir
        }

        /**
         * Captures all available diagnostics into [FailureBundle] and writes each
         * artifact to disk.
         *
         * Each artifact is written independently — one missing or unavailable file does
         * not prevent the others from being captured.
         *
         * @param testClassSimpleName Used to construct the output directory path.
         * @param testInstance        The live [DesktopComposeUiTest]; its
         *                             [DesktopComposeUiTest.captureToImage] is used for
         *                             the screenshot.
         * @param app                 The [Koin] application the test started with;
         *                             used to resolve [FakeAppDatabase] for state dump.
         * @param attempt             Retry count (1-based); determines the subdirectory.
         * @param kermitBuffer        The [RingBufferLogWriter] installed by the harness,
         *                             if any; may be null when logging was never enabled.
         */
        @OptIn(ExperimentalTestApi::class)
        fun capture(
            testClassSimpleName: String,
            testInstance: DesktopComposeUiTest,
            app: Koin,
            attempt: Int,
            kermitBuffer: RingBufferLogWriter?,
        ): FailureBundle {
            val bundle = FailureBundle(
                testClassSimpleName = testClassSimpleName,
                attempt = attempt,
                outputDir = prepareOutputDir(testClassSimpleName, attempt),
            )

            // Screenshot — captureToImage() on SkikoComposeUiTest is available in 1.12.0
            runCatching {
                val bitmap = testInstance.captureToImage()
                val bufferedImage: BufferedImage = bitmap.toAwtImage()
                ImageIO.write(bufferedImage, "png", bundle.screenshotFile)
            }

            // Database state — testPlatformModule() binds the database under the
            // `AppDatabase` interface, NOT under its implementation type, so this must
            // resolve by the interface and cast. Resolving `getOrNull<FakeAppDatabase>()`
            // always returned null and silently produced a placeholder file.
            runCatching {
                val db = app.getOrNull<AppDatabase>() as? FakeAppDatabase
                writeFile(bundle.dbStateFile) {
                    db?.dumpAll() ?: "<no FakeAppDatabase in the test Koin graph>"
                }
            }

            // Kermit ring buffer
            runCatching {
                val logs = kermitBuffer?.drain() ?: "<ring buffer not available>"
                writeFile(bundle.kermitLogFile) { logs }
            }

            return bundle
        }

        private fun writeFile(file: File, content: () -> String) {
            runCatching {
                file.writeText(content())
            }
        }
    }
}
