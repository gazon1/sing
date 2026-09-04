package com.singularity.todo.core.log

import co.touchlab.kermit.Logger

/**
 * Structured logging facade backed by Kermit.
 *
 * Tag-based filtering works out of the box on both Android (Logcat) and JVM
 * (stdout). Avoid using `println` — it bypasses Kermit's tag/log-level
 * filtering and won't be stripped in release builds.
 */
object AppLog {
    private val log = Logger.withTag("App")

    fun w(throwable: Throwable, message: String? = null) {
        log.w(throwable) { message ?: "Error" }
    }

    fun i(message: String) = log.i { message }
    fun d(message: String) = log.d { message }
}
