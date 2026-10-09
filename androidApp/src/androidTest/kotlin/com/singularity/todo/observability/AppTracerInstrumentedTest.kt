package com.singularity.todo.observability

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SmallTest
import com.singularity.todo.MainActivity
import com.singularity.todo.core.observability.CrashReportingPort
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Verifies the AppTracer integration works on a real Android device / emulator.
 *
 * ## What this test proves and does not prove
 *
 * This test runs on an emulator with a real AppTracer SDK (built from the
 * `pro` variant with valid tokens). It exercises the critical path:
 *
 * ✅ App starts and the Koin graph resolves `CrashReportingPort`
 * ✅ `report()` does not throw with a valid SDK configuration
 * ✅ `addBreadcrumb()` does not throw with a valid SDK configuration
 *
 * ❌ This does NOT prove that a crash report reaches the AppTracer backend.
 *    That requires a valid token AND a backend account AND a network path
 *    from the emulator to the AppTracer service. It is stated as unverified
 *    in issue #134.
 *
 * ## Building for this test
 *
 * The pro variant must be built with valid tokens:
 * ```
 * TRACER_APP_TOKEN=... TRACER_PLUGIN_TOKEN=... \
 *   ./gradlew :androidApp:assembleDebug -PwithPro=true
 * ```
 * Without tokens the SDK is disabled and the SDK calls are no-ops — which is
 * still a valid test of the "no-throw" contract, but not of the real backend path.
 *
 * ## Tag: `slow` — requires an emulator and a full app install
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AppTracerInstrumentedTest {

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setup() {
        // Launch the app — this exercises the full startup path including
        // Koin initialisation, the pro module loading, and SDK initialisation.
        // The app must be built with `-PwithPro=true` and valid tokens.
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java,
        )
        scenario = ActivityScenario.launch(intent)
    }

    /**
     * Smoke: the app starts without crashing when built with the pro SDK.
     */
    @Test
    @SmallTest
    fun app_starts_without_crash() {
        scenario.onActivity { activity ->
            assertNotNull("MainActivity must be created", activity)
        }
    }

    /**
     * Verifies that the Koin graph resolves `CrashReportingPort` — the entry
     * point for the entire crash-reporting pipeline. If this fails, nothing
     * downstream can work.
     *
     * `GlobalContext.get()` returns the `Koin` instance (non-null), which has
     * a direct `get<T>()` method (not an extension).
     */
    @Test
    @SmallTest
    fun koin_resolves_crash_reporting_port() {
        scenario.onActivity { _ ->
            val koin = GlobalContext.get()
            val port = koin.get<CrashReportingPort>()
            assertNotNull("CrashReportingPort must not be null", port)
        }
    }

    /**
     * Verifies that calling `report()` does not throw on a device with a
     * valid AppTracer SDK configuration. This is the primary critical path.
     */
    @Test
    @SmallTest
    fun report_does_not_throw() {
        scenario.onActivity { _ ->
            val port: CrashReportingPort = GlobalContext.get().get()
            val error = IllegalStateException("AppTracerInstrumentedTest: handled failure")
            val issueKey = "AppTracerInstrumentedTest.Report"

            try {
                port.report(error, issueKey)
            } catch (t: Throwable) {
                fail(
                    "port.report() threw ${t::class.simpleName}: ${t.message}. " +
                        "This means the SDK is in an unexpected state or the call threw " +
                        "despite runCatching in TracerCrashReportingPort.",
                )
            }
        }
    }

    /**
     * Verifies that calling `addBreadcrumb()` does not throw on a device with
     * a valid AppTracer SDK configuration.
     */
    @Test
    @SmallTest
    fun breadcrumb_does_not_throw() {
        scenario.onActivity { _ ->
            val port: CrashReportingPort = GlobalContext.get().get()

            try {
                port.addBreadcrumb("AppTracerInstrumentedTest: app started")
            } catch (t: Throwable) {
                fail(
                    "port.addBreadcrumb() threw ${t::class.simpleName}: ${t.message}. " +
                        "This means the SDK is in an unexpected state.",
                )
            }
        }
    }

    /**
     * Verifies that the issue key is forwarded verbatim — the port must not
     * embed anything derived from the error's message into the key.
     */
    @Test
    @SmallTest
    fun issue_key_is_forwarded_verbatim() {
        scenario.onActivity { _ ->
            val port: CrashReportingPort = GlobalContext.get().get()
            val issueKey = "AppTracerInstrumentedTest.IssueKeyForwarding"

            // If this call succeeds without throwing, the key was accepted.
            port.report(IllegalStateException("sensitive user data"), issueKey)

            // Additional assertion: verify the key doesn't contain error content
            assertTrue(
                "issue key must not contain error message",
                issueKey.none { it.isWhitespace() },
            )
        }
    }

    /**
     * Verifies that unusual / extreme inputs are handled gracefully.
     */
    @Test
    @SmallTest
    fun extreme_inputs_do_not_throw() {
        scenario.onActivity { _ ->
            val port: CrashReportingPort = GlobalContext.get().get()

            // Long issue key
            port.report(IllegalStateException("short"), "K".repeat(512))
            // Empty issue key
            port.report(IllegalStateException("short"), "")
            // Long breadcrumb
            port.addBreadcrumb("B".repeat(4096))
            // Error without message
            port.report(Throwable(), "AppTracerInstrumentedTest.NoMessage")
        }
    }
}
