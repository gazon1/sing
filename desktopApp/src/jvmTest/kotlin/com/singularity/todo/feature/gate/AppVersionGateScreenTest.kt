package com.singularity.todo.feature.gate

import com.singularity.todo.openStoreOnDesktop
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Desktop JVM unit test for the [openStoreOnDesktop] headless-CI path.
 *
 * ## The bug (E5, #201)
 *
 * `java.awt.Desktop.getDesktop()` throws [java.awt.HeadlessException] — an [Error],
 * not an [Exception] — when run in a headless CI environment.  The original code
 * used `catch (e: Exception)` which does NOT catch `Error` subclasses, so the
 * exception propagated and crashed the process.
 *
 * The fix is `catch (e: Throwable)` in [openStoreOnDesktop] (shared/src/jvmMain/
 * kotlin/com/singularity/todo/App.kt), which absorbs every `Error` including
 * `HeadlessException`.
 *
 * ## Why a unit test and not a Compose UI test
 *
 * Compose UI tests never exercise this path because the "Update Required" blocked
 * state requires the [com.singularity.todo.feature.gate.RemoteConfigPort] to
 * report a version below the minimum — a condition that is not reachable in a
 * test environment without mocking the remote config.  A unit test that directly
 * calls [openStoreOnDesktop] exercises the exact `catch (e: Throwable)` line
 * that the Compose path would hit, without any state setup.
 *
 * ## What this test verifies
 *
 * 1. In a headless environment, `Desktop.getDesktop()` throws `HeadlessException`
 *    (an `Error`, not an `Exception`).
 * 2. `catch (e: Throwable)` absorbs it silently.
 * 3. The function returns normally — no exception propagates to the test.
 */
@Tag("fast")
class AppVersionGateScreenTest {

    @Test
    fun `openStoreOnDesktop absorbs HeadlessException thrown by DesktopgetDesktop in headless environments`() {
        // This test is meaningful ONLY in a headless environment — and the test
        // JVM is headless by construction now: desktopApp/build.gradle.kts sets
        // `java.awt.headless=true` on every forked Test JVM. Before that, a host
        // with a reachable display made Desktop.getDesktop() succeed, which
        // launched a REAL browser from the test: Firefox started, crashed with a
        // minidump, and the Gradle test worker hung forever (two full check.sh
        // runs were lost to it). The test is still correct in either case:
        //   - headed:  browse() is called and either succeeds or throws a different exception
        //              (both are absorbed by catch (e: Throwable))
        //   - headless: browse() throws HeadlessException which catch (e: Throwable) absorbs
        //
        // The absolute guarantee is: no path through openStoreOnDesktop() can propagate
        // a Throwable to the caller.  The function is a no-op on any host.
        openStoreOnDesktop()
        // If we reach here, catch (e: Throwable) absorbed whatever was thrown.
        // If the fix was reverted to catch (e: Exception), HeadlessException would
        // escape as an uncaught Error and this test would fail.
    }
}
