package com.singularity.todo.test.helpers

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.test.fakes.FakeAppDatabase
import org.koin.core.Koin
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import com.singularity.todo.core.error.runCatchingCancellable

/**
 * Dumps the current unmerged semantics tree, or a note if unavailable.
 *
 * Uses the unmerged tree: the whole point of debugging a selector is to see the
 * raw nodes before Compose folds them, and a merged tree hides exactly the
 * duplicate `Text` that makes `onNodeWithText` fail on ambiguity.
 */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.dumpSemantics(): String =
    runCatching { onRoot(useUnmergedTree = true).printToString(maxDepth = 25) }
        .getOrElse { "<semantics tree unavailable: ${it.message}>" }

/**
 * The directory under `build/diagnostics/` where this test's artifacts live.
 *
 * Structure:
 * ```
 * build/diagnostics/<TestClassSimpleName>/
 *   attempt-1/
 *     db-state.txt     — FakeAppDatabase dump
 *     kermit.log       — Kermit ring-buffer contents
 *     coroutines.txt   — kotlinx-coroutines-debug snapshot
 *     steps.txt        — StepRecorder log (empty when no steps recorded)
 *     tree.txt         — semantics tree at failure
 *     nodes.txt         — tagged nodes: tag/text/contentDescription/bounds
 *     screenshot.png    — screen capture
 *     screenshot-annotated.png — screen capture with bounding-box overlays
 *   attempt-2/         — retry only
 *     …
 * ```
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

    /** Coroutine dump via kotlinx-coroutines-debug agent. */
    val coroutinesFile: File get() = outputDir.resolve("coroutines.txt")

    /** Step recorder log — written by StepRecorder. */
    val stepsFile: File get() = outputDir.resolve("steps.txt")

    /** Semantics tree at the moment of failure. */
    val treeFile: File get() = outputDir.resolve("tree.txt")

    /** Nodes with testTag: tag / text / contentDescription / bounds. */
    val nodesFile: File get() = outputDir.resolve("nodes.txt")

    /** Screenshot with bounding-box overlays and tag labels. */
    val screenshotAnnotatedFile: File get() = outputDir.resolve("screenshot-annotated.png")

    /**
     * Adds all captured artifacts to [throwable] as suppressed exceptions so the
     * CI test report displays the paths alongside the failure reason.
     */
    fun addSuppressedTo(throwable: Throwable) {
        val artifacts = listOf(
            screenshotFile,
            dbStateFile,
            kermitLogFile,
            coroutinesFile,
            stepsFile,
            treeFile,
            nodesFile,
            screenshotAnnotatedFile,
        )
        artifacts.forEach { file ->
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
         * Returns the output directory for [testClassSimpleName]/[attempt].
         * Creates the directory if it does not exist. Any prior content is deleted
         * when [capture] starts, so every attempt begins with a clean directory.
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
         * @param steps               The [StepRecorder] for the current test, if any;
         *                             used to write steps.txt in the bundle.
         * @param highlightTag        Tag of the last failed step, if any; passed to
         *                             [captureAnnotated] for red highlighting.
         */
        @OptIn(ExperimentalTestApi::class)
        suspend fun capture(
            testClassSimpleName: String,
            testInstance: DesktopComposeUiTest,
            app: Koin,
            attempt: Int,
            kermitBuffer: RingBufferLogWriter?,
            steps: StepRecorder? = null,
            highlightTag: String? = null,
        ): FailureBundle {
            val bundle = FailureBundle(
                testClassSimpleName = testClassSimpleName,
                attempt = attempt,
                outputDir = prepareOutputDir(testClassSimpleName, attempt),
            )

            // Delete prior content so this attempt starts clean — stale files from a
            // previous attempt that crashed before writing some artifacts must not mislead
            // investigation (e.g., old screenshot showing a different failure state).
            runCatchingCancellable { bundle.outputDir.deleteRecursively() }
            bundle.outputDir.mkdirs()

            // Order: hang-proof artifacts first, screenshot last.
            // captureToImage blocks on EventQueue.invokeAndWait — if the failure left an
            // endless redraw loop running, the EDT never releases and the screenshot hangs.
            // The other artifacts (db, kermit, coroutines) are all non-blocking.

            // Database state — testPlatformModule() binds the database under the
            // `AppDatabase` interface, NOT under its implementation type, so this must
            // resolve by the interface and cast. Resolving `getOrNull<FakeAppDatabase>()`
            // always returned null and silently produced a placeholder file.
            runCatchingCancellable {
                val db = app.getOrNull<AppDatabase>() as? FakeAppDatabase
                writeFile(bundle.dbStateFile) {
                    db?.dumpAll() ?: "<no FakeAppDatabase in the test Koin graph>"
                }
            }

            // Kermit ring buffer
            runCatchingCancellable {
                val logs = kermitBuffer?.drain() ?: "<ring buffer not available>"
                writeFile(bundle.kermitLogFile) { logs }
            }

            // Coroutine dump — kotlinx-coroutines-debug agent snapshot; see CoroutineDiagnostics
            runCatchingCancellable {
                writeFile(bundle.coroutinesFile) {
                    CoroutineDiagnostics.dump(testClassSimpleName, attempt)
                }
            }

            // Step recorder log — always written (empty when no steps recorded)
            runCatchingCancellable {
                writeFile(bundle.stepsFile) {
                    steps?.format() ?: ""
                }
            }

            // Semantics tree — written as a file so it is available in the bundle
            // directory without needing to parse the JUnit XML report. The unmerged tree
            // is used because it shows the raw nodes before Compose folds them.
            runCatchingCancellable {
                writeFile(bundle.treeFile) {
                    testInstance.dumpSemantics()
                }
            }

            // Screenshot — captureToImage() on SkikoComposeUiTest is available in 1.12.0.
            //
            // captureToImage blocks on EventQueue.invokeAndWait, so a failure that left
            // an endless redraw loop running never releases it, and a coroutine timeout
            // cannot cancel a blocking EDT wait — the diagnostic path itself hung and
            // masked the real failure. Freezing the frame clock first breaks BOTH known
            // hang modes: recomposition and animation invalidations are delivered as
            // frame-clock callbacks, and a frozen clock stops scheduling them, so the
            // composition reaches "idle" immediately and the capture proceeds. The
            // screenshot shows the last composed frame — exactly what diagnosis needs.
            // -Dsingularity.test.screenshot=false still skips the capture entirely.
            runCatchingCancellable { testInstance.mainClock.autoAdvance = false }
            if (System.getProperty("singularity.test.screenshot") != "false") {
                runCatchingCancellable {
                    val bitmap = testInstance.captureToImage()
                    val bufferedImage: BufferedImage = bitmap.toAwtImage()
                    ImageIO.write(bufferedImage, "png", bundle.screenshotFile)
                }
                // Annotated screenshot: bounding-box overlays on the same frame.
                // Writes nodes.txt unconditionally (text fallback) and screenshot-annotated.png
                // when screenshot is enabled. Both are independent runCatching blocks so one
                // failure does not affect the other.
                runCatchingCancellable {
                    testInstance.captureAnnotated(
                        highlightTag = highlightTag,
                        file = bundle.screenshotAnnotatedFile,
                        nodesFile = bundle.nodesFile,
                    )
                }
            } else {
                // Still write nodes.txt even when screenshot is disabled.
                runCatchingCancellable {
                    testInstance.captureAnnotated(
                        highlightTag = highlightTag,
                        file = bundle.screenshotAnnotatedFile,
                        nodesFile = bundle.nodesFile,
                    )
                }
            }

            // Regression diff vs the baseline snapshot written on a passing run with
            // -Dsingularity.test.baseline=true. Best-effort on both sides: no baseline
            // (or a failed read) simply means no diff.
            runCatchingCancellable {
                val baselineDir = File("build/diagnostics/$testClassSimpleName/baseline")
                val diff = testInstance.diffAgainstBaseline(baselineDir)
                if (diff != null) {
                    File(bundle.outputDir, "nodes-diff.txt").writeText(diff)
                }
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
