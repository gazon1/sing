package com.singularity.todo.core.version

/**
 * Android implementation: hardcoded for MR-1 prototype.
 *
 * TODO[MR-2]: Replace hardcoded values with Koin-injected [PackageInfo]
 * resolution. See [docs/decisions/2026-09-23-versioning-and-runtime-gates.md]
 * § Consequences for the canonical wiring approach.
 *
 * This is an explicit technical debt marker. The current values match
 * [androidApp/build.gradle.kts] versionName/versionCode for convenience only.
 */
actual fun appVersion(): AppVersion = AppVersion(
    name = "0.1.0",
    code = 1,
)
