// SPDX-License-Identifier: FSL-1.1-ALv2
//
// Part of the source-available `pro` catalogue, not the Apache-2.0 core.
// Full terms: LICENSE.pro at the repository root.
//
// This file is compiled only under `-PwithPro=true` (see androidApp/build.gradle.kts)
// and is named by the `appClass` manifest placeholder in that configuration only.

package com.singularity.todo.pro

import com.singularity.todo.SingularityApp
import com.singularity.todo.pro.observability.proObservabilityModule
import org.koin.core.module.Module
import ru.ok.tracer.HasTracerConfiguration
import ru.ok.tracer.TracerConfiguration
import ru.ok.tracer.crash.report.CrashFreeConfiguration
import ru.ok.tracer.crash.report.CrashReportConfiguration

/**
 * The pro build's [Application]: [SingularityApp] plus vendor crash reporting.
 *
 * ## Why this is a subclass rather than a flag inside `SingularityApp`
 *
 * `ru.ok.tracer` requires the `Application` to implement `HasTracerConfiguration`, and
 * the SDK type-checks for it. There is no way to satisfy that interface without the
 * proprietary types appearing in the class's signature — so a `if (withPro)` branch
 * inside the Apache-2.0 class would have put a vendor type in an Apache-2.0 file even
 * though the branch never executes. Splitting the class is what keeps the free build
 * free of proprietary code *as source*, not merely at runtime.
 *
 * ## Why the base class stays open
 *
 * Because of this subclass, and nothing else. The base class carries a KDoc note saying
 * so, because "an Apache-2.0 class that exists to be subclassed by the pro build" is a
 * licence-boundary seam, and seams are where boundaries erode.
 *
 * ## Reading order
 *
 * The SDK reads [tracerConfiguration] exactly once per process, after `attachBaseContext`
 * and **before** [onCreate]. Nothing here may touch state that `onCreate` initialises —
 * the context is available, Koin and logging are not. [tracerConfiguration] is a property
 * with a getter rather than an initialised field for that reason: it must be answerable
 * before the object's own initialisation has run.
 */
class ProSingularityApp :
    SingularityApp(),
    HasTracerConfiguration {

    /**
     * AppTracer plugin configuration.
     *
     * `setSendAnr` and `setExperimentalNonFatalRateLimitEnabled` are stated explicitly
     * rather than left implicit: both are the vendor's recommended settings, and a
     * reviewer should be able to see that a deliberate choice was made rather than a
     * default inherited. Crash-free is left at its default (enabled) because its only
     * meaningful switch is `setEnabled`, and a disabled crash-free metric is worse than
     * none — it would read as a healthy number.
     */
    override val tracerConfiguration: List<TracerConfiguration>
        get() = listOf(
            CrashReportConfiguration.build {
                setSendAnr(true)
                setExperimentalNonFatalRateLimitEnabled(true)
            },
            CrashFreeConfiguration.build { /* defaults: enabled */ },
        )

    /**
     * Replaces the free core's `CrashReportingPort` with the vendor-backed one.
     *
     * `platformModule()` binds `FileCrashReportingPort`; modules returned here are loaded
     * after it, so this binding wins. The free build resolves the same interface to the
     * log-file implementation and never sees this class.
     */
    override fun extraKoinModules(): List<Module> = listOf(proObservabilityModule())
}
