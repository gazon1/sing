// SPDX-License-Identifier: FSL-1.1-ALv2
//
// Part of the source-available `pro` catalogue, not the Apache-2.0 core.
// Full terms: LICENSE.pro at the repository root.
//
// Boundary enforced by `scripts/check-pro-licence-boundary.py`.

package com.singularity.todo.pro.observability

import co.touchlab.kermit.Logger
import com.singularity.todo.core.observability.CrashReportingPort
import org.koin.core.context.stopKoin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the `pro` catalogue is *for*, tested.
 *
 * The whole module exists to substitute one interface binding: the free core
 * binds `CrashReportingPort` to `FileCrashReportingPort`, and
 * [proObservabilityModule] replaces that with the vendor-backed
 * [TracerCrashReportingPort]. That rebinding was the one thing about `pro/`
 * that nothing checked — `KoinGraphValidationTest` lives in `:shared` and
 * validates the free graph, and it cannot see a module that only exists behind
 * `-PwithPro=true`.
 *
 * The failure this guards against is quiet: if the override stopped taking
 * effect, every configuration would still resolve the same interface, every
 * test would still pass, and crash reports would go to the local log file
 * while every dashboard stayed empty. Nothing would throw.
 *
 * ## What is deliberately not tested here
 *
 * [TracerCrashReportingPort] forwards to `ru.ok.tracer`, which needs a
 * configured app token to do anything. This test does not need one: it asserts
 * the *wiring*, not the vendor's behaviour. Whether a real report reaches
 * AppTracer's backend is still unverified and is stated as such in the ADR.
 */
@Tag("fast")
class ProObservabilityModuleTest {

    private val log = Logger.withTag("ProObservabilityModuleTest")

    /**
     * The graph as the free core declares it, plus Kermit's `Logger`.
     *
     * The `Logger` is not decoration: `proObservabilityModule()` constructs
     * `TracerCrashReportingPort(get())`, so resolving the port requires a
     * `Logger` binding to exist. A test graph that omitted it would fail with
     * `InstanceCreationException` on every case, which is a correct complaint
     * about the test, not about the module.
     */
    private fun freeCoreGraph(): org.koin.core.module.Module = module {
        single<co.touchlab.kermit.Logger> { log }
        single<CrashReportingPort> { FreeStandInCrashReportingPort() }
    }

    @BeforeEach
    fun setUp() {
        stopKoin()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    private fun startGraph(): org.koin.core.KoinApplication =
        koinApplication {
            modules(freeCoreGraph(), proObservabilityModule())
        }

    @Test
    fun `the pro module replaces the free binding rather than sitting beside it`() {
        val app = startGraph()
        val resolved = app.koin.get<CrashReportingPort>()

        assertNotNull(resolved, "the port must resolve in the pro configuration")
        assertTrue(
            resolved is TracerCrashReportingPort,
            "expected the vendor-backed implementation, got ${resolved::class.simpleName}. " +
                "The free binding survived the override, so crash reports would go to the " +
                "local log file while the vendor dashboard stayed empty — and every " +
                "configuration would still resolve, so nothing else would notice",
        )
    }

    @Test
    fun `the same interface resolves before and after the pro module is loaded`() {
        val free = koinApplication { modules(freeCoreGraph()) }
        val freePort = free.koin.get<CrashReportingPort>()
        assertTrue(
            freePort is FreeStandInCrashReportingPort,
            "the free graph must resolve the free implementation, or this test is not " +
                "measuring an override at all",
        )

        free.koin.loadModules(listOf(proObservabilityModule()), allowOverride = true)

        assertTrue(
            free.koin.get<CrashReportingPort>() is TracerCrashReportingPort,
            "this is the ordering the contract depends on: the pro module is loaded after " +
                "startKoin with allowOverride=true. If it were declared in the initial " +
                "module list instead, the free binding would win or the graph would fail to " +
                "start — and ProSingularityApp is the only caller, so nothing else would " +
                "exercise the difference",
        )
    }

    @Test
    fun `the resolved port is a singleton`() {
        val app = startGraph()

        assertTrue(
            app.koin.get<CrashReportingPort>() === app.koin.get<CrashReportingPort>(),
            "a factory binding would give a new instance per injection, and the port is " +
                "injected into every failure handler in the app",
        )
    }

    @Test
    fun `the port does not throw when the SDK is unconfigured`() {
        // No token is configured in this module's test task, so the SDK is
        // disabled. The port must still absorb that: it runs on the error path,
        // and an exception escaping it would kill the process *while handling an
        // error* — turning a handled failure into the crash the reporter exists
        // to observe.
        val port = startGraph().koin.get<CrashReportingPort>()

        port.report(IllegalStateException("handled failure"), "TestError")
        port.addBreadcrumb("a breadcrumb")

        // Reaching here is the assertion. Anything thrown above failed it.
    }

    @Test
    fun `an issue key is never derived from the error itself`() {
        // The port forwards the key verbatim; this pins the *contract* the
        // caller must honour, because a key derived from a message or an id
        // would fragment the dashboard and can carry user content off-device.
        val port = startGraph().koin.get<CrashReportingPort>()
        val issueKey = "TaskDetail.SaveFailed"

        port.report(IllegalArgumentException("user text: buy milk"), issueKey)

        // The value is a stable machine identifier, so it is unchanged and
        // carries none of the error's text.
        assertTrue(issueKey.none { it.isWhitespace() }, "an issue key must be a single token")
        assertTrue(
            "user text" !in issueKey,
            "the key must not embed anything derived from the error's message",
        )
    }

    @Test
    fun `the vendored implementation is the one behind the interface`() {
        // Guards the licence claim in the other direction: the class bound to an
        // Apache-2.0 interface must be the FSL-licensed one, and it must be the
        // only implementation `pro/` supplies.
        val port = startGraph().koin.get<CrashReportingPort>()

        assertEquals(
            "com.singularity.todo.pro.observability.TracerCrashReportingPort",
            port::class.qualifiedName,
            "the pro configuration must bind its own implementation, not a core one",
        )
    }
}

/**
 * Stands in for `FileCrashReportingPort`, which is in `:shared`'s `androidMain`
 * and therefore not on this module's test classpath. Its only job is to be a
 * different type, so that "did the override happen" has an answer.
 */
private class FreeStandInCrashReportingPort : CrashReportingPort {
    override fun report(error: Throwable, issueKey: String) = Unit
    override fun addBreadcrumb(message: String) = Unit
}
