package com.singularity.todo.core.error

import co.touchlab.kermit.Logger

/**
 * Wraps [runCatching] and logs the failure via [Logger] before returning [Result.failure].
 * Lazy message avoids string construction when severity is below the configured minimum.
 *
 * Usage:
 * ```
 * runCatchingLogged(log) { "[id=$id op=save]" } {
 *     taskDao.insert(entity)
 * }
 * ```
 *
 * In release builds (minSeverity = Warn) the lazy block is never evaluated,
 * so there is zero overhead.
 */
inline fun <T> runCatchingLogged(
    log: Logger,
    context: () -> String = { "" },
    block: () -> T
): Result<T> = runCatching(block).onFailure { e ->
    log.e(e) { "Operation failed ${context().ifBlank { "" }}" }
}
