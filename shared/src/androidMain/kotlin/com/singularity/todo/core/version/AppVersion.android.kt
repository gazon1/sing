package com.singularity.todo.core.version

/**
 * Android implementation: hardcoded for MR-1 prototype.
 *
 * MR-2 will wire this through Koin DI so androidApp injects its actual
 * BuildConfig.VERSION_NAME / VERSION_CODE at startup — see
 * [docs/decisions/2026-09-23-versioning-and-runtime-gates.md § Consequences].
 */
actual fun appVersion(): AppVersion = AppVersion(
    name = "0.1.0",
    code = 1,
)
