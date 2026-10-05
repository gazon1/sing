// SPDX-License-Identifier: FSL-1.1-ALv2
//
// Part of the source-available `pro` catalogue, not the Apache-2.0 core.
// Full terms: LICENSE.pro at the repository root.
//
// Boundary enforced by `scripts/check-pro-licence-boundary.py`.

package com.singularity.todo.pro.observability

import com.singularity.todo.core.observability.CrashReportingPort
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Re-binds [CrashReportingPort] to the vendor-backed [TracerCrashReportingPort].
 *
 * Loaded by `ProSingularityApp.extraKoinModules()` *after* `startKoin`, with
 * `allowOverride = true`, so this binding replaces the free core's
 * `FileCrashReportingPort` rather than colliding with it. That ordering is the whole
 * contract: both configurations resolve the same interface, and the difference between
 * them is which class ends up behind it.
 *
 * The module lives here rather than in `ProSingularityApp` for one concrete reason: it is
 * the only place that has to name both `TracerCrashReportingPort` and the Kermit `Logger`
 * it is constructed with. Writing the binding in the app module would drag Kermit — a
 * third-party type — onto the app's compile classpath purely to spell out a `get()`.
 */
fun proObservabilityModule(): Module = module {
    single<CrashReportingPort> { TracerCrashReportingPort(get()) }
}
