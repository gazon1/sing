package com.singularity.todo.ui

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.singularity.todo.BuildConfig
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.io.FileInputStream

/**
 * What was on screen when an instrumented test failed, captured where CI can find it.
 *
 * ## Why the Android layer needed this
 *
 * The desktop Compose tests have had a failure bundle for a while
 * (`desktopApp/src/jvmTest/.../test/helpers/FailureBundle.kt`), and CI already
 * uploads it (the `desktop-diagnostics` artifact). The instrumented suite had
 * nothing: a red run produced a JUnit XML with a message and a stack trace, and
 * a logcat that is a wall of lines from every process on the device. The
 * question every failing instrumented test raises — *what was on screen, and how
 * far had the test got* — had no answer.
 *
 * The answer matters here more than on the desktop, because the instrumented
 * suite is the layer that fails for reasons the app does not own: a loaded CI
 * runner, a slow cold start, an emulator that lost its graphics stack. A failure
 * message that names the screen and the steps is the difference between "the app
 * is broken" and "the runner was busy".
 *
 * ## Where the bundle lives, and why not in the app's storage
 *
 * AGP uninstalls the app under test when the run ends, and an app's own
 * directory is deleted with it. A bundle written there is gone before CI can
 * look at it — which is why the desktop bundle works (the desktop test writes
 * to the *host's* `build/diagnostics`) and why the obvious Android equivalent
 * does not.
 *
 * So the bundle is written to the app's external directory and then copied to
 * `/data/local/tmp`, which belongs to the shell user and survives the
 * uninstall. The copy runs through `UiAutomation.executeShellCommand` — the
 * instrumentation has no other way to write outside its own sandbox.
 *
 * The result is pulled off the device by `scripts/run-android-ui-tests.py` into
 * `androidApp/build/diagnostics/`, which the workflow uploads.
 *
 * @property testClassSimpleName the class the bundle belongs to.
 * @property attempt the retry number, 1-based.
 * @property outputDir the directory the artifacts were written to.
 */
internal class FailureBundle(val testClassSimpleName: String, val attempt: Int, val outputDir: File) {
    /**
     * An artifact of this bundle.
     *
     * The files are flat in the app's external directory and carry the class and
     * the attempt in their name, because a subdirectory the app creates there is
     * mode 0700 — the app's umask is 0077 — and the shell cannot traverse it.
     * The external directory itself is group-readable by `ext_data_rw`, and the
     * shell is in that group, so a file directly in it can be read.
     */
    private fun artifact(name: String): File =
        outputDir.resolve("$testClassSimpleName-attempt-$attempt-$name")

    /** Screen capture at the moment of failure. */
    val screenshotFile: File get() = artifact("screenshot.png")

    /** The accessibility hierarchy at the moment of failure. */
    val treeFile: File get() = artifact("tree.txt")

    /** The step log of the test that failed. */
    val stepsFile: File get() = artifact("steps.txt")

    /** The environment the test ran in: bypass, session, device, build. */
    val environmentFile: File get() = artifact("environment.txt")

    /** The directory the bundle was left in on the device. */
    val devicePath: String get() = "$DEVICE_ROOT/$testClassSimpleName/attempt-$attempt"

    /**
     * The bundle as it appears in a failure message.
     *
     * The message is the part of a failed run that reaches a reader without
     * opening an artifact, so it carries the two facts that answer "why": where
     * the bundle is, and what the test had done. The screenshot and the tree
     * are named, not inlined — they are files, and their path is the pointer.
     */
    fun describe(): String = buildString {
        appendLine("diagnostics: $devicePath")
        appendLine("  screenshot: ${if (screenshotFile.exists()) "captured" else "not captured"}")
        appendLine("  screen: ${if (treeFile.exists()) "captured" else "not captured"}")
        val steps = stepsFile.takeIf { it.exists() }?.readText().orEmpty()
        if (steps.isNotBlank()) {
            appendLine("  steps:")
            append(steps)
        }
    }

    /**
     * Adds the bundle to [throwable] as suppressed exceptions.
     *
     * JUnit prints suppressed exceptions under the failure, so the artifact
     * names reach the test report without anyone opening a file — and the report
     * is what CI archives.
     */
    fun addSuppressedTo(throwable: Throwable) {
        throwable.addSuppressed(Exception("<diagnostics: $devicePath>"))
        for (file in listOf(screenshotFile, treeFile, stepsFile, environmentFile)) {
            val state = if (file.exists()) "available" else "unavailable"
            throwable.addSuppressed(Exception("<$state: ${file.name}>"))
        }
    }

    companion object {

        /**
         * Where the bundle is left on the device.
         *
         * The shell's directory, not the app's: the app is uninstalled when the
         * run ends, and what it wrote goes with it.
         */
        const val DEVICE_ROOT = "/data/local/tmp/singularity-diagnostics"

        /**
         * Captures the diagnostics of a failed test.
         *
         * Every artifact is captured independently: a screenshot that cannot be
         * taken must not cost the tree, the steps and the environment.
         *
         * @param device the device to capture from, or null when the test never
         *   drove the UI. A null device costs the screenshot and the tree and
         *   leaves the steps and the environment — the two artifacts that do not
         *   need a screen.
         * @param testClassSimpleName the class the bundle belongs to.
         * @param attempt the retry number, 1-based.
         * @param steps the step recorder of the test that failed, if any.
         */
        fun capture(
            device: UiDevice?,
            testClassSimpleName: String,
            attempt: Int = 1,
            steps: StepRecorder? = null,
        ): FailureBundle {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            // The app's EXTERNAL directory, not its private one: the shell can
            // read the external one and cannot read the private one, and the
            // shell is what copies the bundle out.
            //
            // Without an external directory there is nowhere the shell could
            // read the bundle from, so the private one is used and the bundle
            // stays local: the failure message still names the artifacts, and
            // the publish step simply finds nothing it can copy.
            val externalRoot =
                instrumentation.targetContext.getExternalFilesDir(null)
                    ?: instrumentation.targetContext.cacheDir
            val bundle = FailureBundle(testClassSimpleName, attempt, externalRoot)

            // The screen first: it is the artifact that cannot be reconstructed
            // later, and the one that answers "what was on screen".
            if (device != null) {
                runCatching { device.takeScreenshot(bundle.screenshotFile) }
                runCatching { bundle.treeFile.writeText(device.dumpHierarchy()) }
            }

            runCatching { bundle.stepsFile.writeText(steps?.format().orEmpty()) }

            runCatching { bundle.environmentFile.writeText(environment()) }

            // The copy to the shell's directory is what makes the bundle
            // outlive the uninstall — and it is the only step that can fail
            // without making the others worthless.
            runCatching { publish(bundle, testClassSimpleName, attempt) }

            return bundle
        }

        /**
         * The environment the test ran in.
         *
         * The four facts that separate "the app is broken" from "the build was
         * wrong" from "the runner was slow": whether the auth bypass was in the
         * APK, which session the app was in, what the device was, and what was
         * built.
         */
        private fun environment(): String = buildString {
            appendLine("auth bypass (BuildConfig.TEST_AUTH_BYPASS): ${BuildConfig.TEST_AUTH_BYPASS}")
            appendLine("session: ${sessionDescription()}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
            appendLine("build: ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
        }

        /**
         * Copies the captured artifacts to the shell's directory on the device.
         *
         * ## Why this is not `cp`
         *
         * `UiAutomation.executeShellCommand` does not run the command through a
         * shell: the string is split on whitespace and the pieces become the
         * arguments of the program. There is no redirection, no pipe and no
         * `&&` — `echo hi > /some/file` prints `hi > /some/file` and writes
         * nothing. Quoting does not help either: the quotes arrive as part of
         * the argument, so the path is looked up with a quote in its name.
         *
         * `sh -c` is the way back to a shell, and the script it takes has to be
         * a single argument — so the script may not contain a space. That is
         * why the copy is `cat<source>>destination`: `cat` with its input
         * redirected from the source and its output redirected to the
         * destination copies the file byte for byte, spaces and all. The `<`
         * is not optional — without it `cat/source` is the name of a program
         * that does not exist, and the redirection still creates an empty file.
         */
        private fun publish(bundle: FailureBundle, testClassSimpleName: String, attempt: Int) {
            val destination = "$DEVICE_ROOT/$testClassSimpleName/attempt-$attempt"
            shell("mkdir -p $destination")
            for (file in listOf(bundle.screenshotFile, bundle.treeFile, bundle.stepsFile, bundle.environmentFile)) {
                if (!file.exists()) continue
                val name = file.name.substringAfterLast("-attempt-$attempt-")
                shell("sh -c cat<${file.absolutePath}>$destination/$name")
            }
        }

        /**
         * Runs a command on the device and waits for it to finish.
         *
         * The output is drained before the descriptor is closed: a command that
         * still has something to say and nobody reading it blocks forever.
         */
        private fun shell(command: String) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            descriptor.close()
        }
    }
}

/**
 * Captures the failure bundle when a test fails.
 *
 * The rule is what turns "a test failed" into "here is what the device looked
 * like when it did". It is a rule rather than a `try`/`catch` in every test
 * because a test that forgets the catch is a test that fails without a bundle,
 * and the failure is exactly the case where the bundle is needed.
 *
 * @param device the device to capture from; resolved lazily, because the device
 *   handle is not available while the rule is constructed. Null when the test
 *   never drove the UI — the bundle is then captured without the screen.
 * @param steps the recorder of the test that failed. It is taken as a provider
 *   rather than read from the thread-local at failure time, because the
 *   teardown clears the thread-local before the rule runs.
 */
class FailureBundleRule(
    private val device: () -> UiDevice?,
    private val steps: () -> StepRecorder? = { stepRecorder() },
) : TestWatcher() {

    override fun failed(e: Throwable, description: Description) {
        val testClass = description.className.substringAfterLast('.').ifEmpty { "UnknownTest" }
        val bundle =
            runCatching {
                FailureBundle.capture(
                    device = device(),
                    testClassSimpleName = testClass,
                    attempt = 1,
                    steps = steps(),
                )
            }.getOrNull() ?: return
        bundle.addSuppressedTo(e)
    }
}
