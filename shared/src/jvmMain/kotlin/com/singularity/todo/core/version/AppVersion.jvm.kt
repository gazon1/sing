package com.singularity.todo.core.version

/**
 * JVM/Desktop implementation: reads the version from the system property
 * `-Dsingularity.version=<name>` passed by the desktop host application.
 *
 * Desktop hosts should pass `-Dsingularity.version=%version%` in their
 * `compose.desktop.application.jvmArgs` (or equivalent) so that
 * `appVersion().name` reflects the actual packaged version.
 *
 * If the property is absent (e.g. running from IDE without the flag),
 * falls back to "0.0.0-dev" — this is safe because the version gate
 * only activates when a remote config provides `minSupportedVersion`.
 */
actual fun appVersion(): AppVersion {
    val name: String = System.getProperty("singularity.version") ?: "0.0.0-dev"
    // Default code = 0 for unknown/dev versions.
    // The desktop host should set the system property to match its release version.
    val code: Int = System.getProperty("singularity.version.code")?.toIntOrNull() ?: 0
    return AppVersion(name = name, code = code)
}
