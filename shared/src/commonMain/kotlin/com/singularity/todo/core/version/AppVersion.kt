package com.singularity.todo.core.version

import kotlinx.serialization.Serializable

/**
 * Canonical app version for the running process.
 *
 * Single source of truth — all code that needs the running app's version
 * must use [appVersion()], not a hardcoded literal.
 *
 * Android: reads BuildConfig.VERSION_NAME / VERSION_CODE at runtime via Class.forName.
 * JVM/Desktop: reads -Dsingularity.version system property; falls back to "0.0.0".
 */
@Serializable
data class AppVersion(val name: String, val code: Int) {
    /**
     * Compares by [code] (semver-like: higher code = newer version).
     * Lexicographic name comparison is intentionally not used.
     */
    operator fun compareTo(other: AppVersion): Int = this.code.compareTo(other.code)

    override fun toString(): String = name
}

/**
 * Returns the version of the currently running application.
 */
expect fun appVersion(): AppVersion
