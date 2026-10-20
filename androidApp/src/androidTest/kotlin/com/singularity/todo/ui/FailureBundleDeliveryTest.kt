package com.singularity.todo.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * The failure bundle really lands where CI looks for it.
 *
 * The bundle is worthless if it stays on the device: AGP uninstalls the app
 * when the run ends, and the directory the app can write to is deleted with
 * it. The bundle therefore travels to the shell's directory, and CI pulls it
 * from there — but every part of that chain is an assumption about what an
 * instrumentation process is allowed to do, and an assumption that is wrong
 * fails silently: the test that failed reports nothing extra, and the artifact
 * directory is simply empty.
 *
 * So the chain is asserted here, on a device, in the same suite that produces
 * the bundle.
 *
 * The mechanics this test pins down are non-obvious and were measured:
 *
 * - `UiAutomation.executeShellCommand` does not run the command through a
 *   shell. The string is split on whitespace and the pieces become arguments,
 *   so there is no redirection and no quoting; `sh -c` with a space-free
 *   script is the way back to a shell.
 * - The shell cannot read the app's private directory, and can read the app's
 *   external one.
 * - `/data/local/tmp` belongs to the shell and outlives the uninstall, which
 *   is the only reason the bundle survives to be pulled.
 */
@RunWith(AndroidJUnit4::class)
class FailureBundleDeliveryTest {

    private fun shell(command: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        val output = FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        descriptor.close()
        return output
    }

    @Test
    @SmallTest
    fun the_bundle_is_published_to_the_shells_directory() {
        val device: UiDevice = awaitUiDevice()
        val testClass = "BundleDeliveryProbe"
        val destination = "${FailureBundle.DEVICE_ROOT}/$testClass/attempt-1"

        // A stale bundle from an earlier run would make this pass for the wrong
        // reason, so the destination is emptied first.
        shell("rm -rf ${FailureBundle.DEVICE_ROOT}/$testClass")

        val bundle = FailureBundle.capture(device = device, testClassSimpleName = testClass)

        assertTrue(
            "the bundle was not written to the app's own directory: ${bundle.outputDir}",
            bundle.environmentFile.exists(),
        )

        val published = shell("ls $destination")
        assertTrue(
            "the bundle did not reach the shell's directory ($destination): [$published]",
            published.contains("environment.txt"),
        )
        assertTrue(
            "the step log did not reach the shell's directory: [$published]",
            published.contains("steps.txt"),
        )

        val environment = shell("cat $destination/environment.txt")
        assertTrue(
            "the published environment file is empty: [$environment]",
            environment.contains("auth bypass"),
        )

        // The screenshot is binary, and the copy is byte for byte: a PNG that
        // arrived truncated would be worse than no PNG at all.
        val size = shell("wc -c < $destination/screenshot.png").trim()
        assertTrue("the published screenshot is empty: [$size]", size.isNotEmpty() && size != "0")
    }

    @Test
    @SmallTest
    fun the_published_directory_is_outside_the_apps_own_storage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appDir = instrumentation.targetContext.getExternalFilesDir(null)?.absolutePath.orEmpty()
        assertTrue(
            "the bundle must not live in the app's storage — it is deleted with the app: $appDir",
            !FailureBundle.DEVICE_ROOT.startsWith(appDir),
        )
    }
}
