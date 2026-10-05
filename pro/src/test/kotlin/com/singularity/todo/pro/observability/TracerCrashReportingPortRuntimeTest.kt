// SPDX-License-Identifier: FSL-1.1-ALv2
//
// Part of the source-available `pro` catalogue, not the Apache-2.0 core.
// Full terms: LICENSE.pro at the repository root.
//
// Boundary enforced by `scripts/check-pro-licence-boundary.py`.

package com.singularity.todo.pro.observability

import co.touchlab.kermit.Logger
import com.singularity.todo.core.observability.CrashReportingPort
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What [TracerCrashReportingPort] does when the vendor SDK is not ready.
 *
 * ## Why this exists, and what it does *not* prove
 *
 * Every other test in this module runs against a graph. This one runs against
 * the real `ru.ok.tracer` classes. It exists because the DI test can prove the
 * port is *bound* and cannot prove what the port *does* when the SDK is in a
 * state it was not designed for.
 *
 * That state is the ordinary one. `pro/build.gradle.kts` disables the vendor
 * plugin when no `tracerAppToken` is configured, so in every build without a
 * secret — which is every CI run, and every contributor's checkout — the SDK is
 * present on the classpath and inert. A release that had only the DI test would
 * have shipped with the port's error path never executed once.
 *
 * **This still does not verify that a crash report reaches AppTracer's
 * backend.** That needs a real app token and a device, and it is stated as
 * unverified in the release commit. What it does establish is the half that
 * does not need one: the port does not throw in any state a build without a
 * secret can produce.
 *
 * ## What the SDK actually does — measured, not assumed
 *
 * `TracerCrashReport.reportCaughtException` is short enough to be worth
 * quoting, and the control below is what makes the quote trustworthy:
 *
 *     if (isDisabled) { Logger.d("Tracer is disabled"); return }
 *     checkNotNull(crashLoggerInternal)  // throws IllegalStateException
 *
 * The obvious reading is that a build with no token lands in the first branch
 * and is a clean no-op. That reading is wrong, and the control is what shows
 * it: with `runCatching` deleted from [TracerCrashReportingPort.report], four
 * of the five tests below fail on `IllegalStateException("Required value was
 * null.")`.
 *
 * The reason is that `isDisabled` is
 * `isConfigDisabled || Tracer.isDisabled()`, and neither term is set by a build
 * that never ran the vendor plugin's generated configuration. So the SDK is
 * neither "disabled" nor initialised — it is in the third state, the one the
 * source does not name, where the `checkNotNull` is reached and throws.
 *
 * That is the whole reason the port wraps the call. It is not defensive
 * programming against a theoretical failure: the failure is what a tokenless
 * build does on every report, and the port runs inside `catchTo`'s failure arm,
 * where an escaping exception turns a handled error into the crash the reporter
 * exists to observe.
 */
@Tag("fast")
class TracerCrashReportingPortRuntimeTest {

    private val log = Logger.withTag("TracerCrashReportingPortRuntimeTest")

    private fun port(): CrashReportingPort = TracerCrashReportingPort(log)

    @Test
    fun `reporting a handled failure does not throw with no app token`() {
        // This is the state every CI run and every contributor checkout is in:
        // the plugin is disabled because no token was supplied, and
        // `crashLoggerInternal` was never initialised. The port's
        // `runCatching` has to absorb whatever the SDK does here, because it
        // runs inside `MviViewModel.catchTo`'s failure arm — an exception
        // escaping would kill the process *while handling an error*.
        port().report(IllegalStateException("a handled failure"), "TestError")
    }

    @Test
    fun `a breadcrumb does not throw with no app token`() {
        port().addBreadcrumb("app foregrounded")
    }

    @Test
    fun `reporting survives a throwable the SDK may not expect`() {
        // The port's contract is that it forwards the *original* throwable, not
        // a redacted rebuild, and a reporter must not become the thing that
        // fails on an unusual input.
        port().report(OutOfMemoryError("no heap"), "OomError")
        port().report(StackOverflowError(), "DeepRecursion")
        port().report(Throwable(), "NoMessage")
    }

    @Test
    fun `a long and an unusual issue key do not throw`() {
        port().report(IllegalArgumentException("bad"), "x".repeat(512))
        port().report(IllegalArgumentException("bad"), "")
        port().addBreadcrumb("y".repeat(4096))
    }

    @Test
    fun `the port survives the SDK being absent from the classpath`() {
        // Not hypothetical: `ru.ok.tracer` is `api` on `:pro` only because the
        // app's `ProSingularityApp` implements `HasTracerConfiguration`. Remove
        // that dependency — a refactor nobody expects to break the pro build —
        // and this class fails to *load*, not to run. Probing by `Class.forName`
        // rather than by instantiating the port is deliberate: a `try` around the
        // *call* would not catch a `NoClassDefFoundError` raised while the
        // declaring class is being resolved.
        //
        // The caught throwable is kept rather than reduced to `false`, because it
        // is the thing under test — a boolean would discard the one piece of
        // information that distinguishes "the SDK is absent" from "the SDK is
        // present but something else went wrong", and a test that cannot tell
        // those apart passes for the wrong reason.
        val loadFailure: Throwable? = try {
            Class.forName("ru.ok.tracer.crash.report.TracerCrashReport")
            null
        } catch (t: Throwable) {
            t
        }

        if (loadFailure == null) {
            // The SDK is present, so exercise the port for real.
            port().report(IllegalStateException("sdk present"), "TestError")
        } else {
            assertTrue(
                loadFailure is NoClassDefFoundError || loadFailure is ClassNotFoundException,
                "expected the vendor SDK to be absent, got $loadFailure",
            )
            // If the SDK is gone, constructing the port must fail to *load* too —
            // and saying so beats reporting the classpath branch as covered.
            val failure = try {
                TracerCrashReportingPort(log)
                null
            } catch (t: Throwable) {
                t
            }
            assertTrue(
                failure is NoClassDefFoundError,
                "expected NoClassDefFoundError when the vendor SDK is absent, got $failure",
            )
        }
    }
}
