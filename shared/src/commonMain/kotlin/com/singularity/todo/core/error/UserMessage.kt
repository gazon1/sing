package com.singularity.todo.core.error

/**
 * Extracts a human-readable message from a [Throwable].
 *
 * Prefers [AppError.message], then [Throwable.message], then [fallback].
 * Returns `"Something went wrong"` when everything is null and no fallback is supplied.
 *
 * Use in UI error surfaces:
 * ```
 * .onFailure { emit(UiEvent.ShowError(it.toMessage())) }
 * .onFailure { emit(UiEvent.ShowError(it.toMessage(fallback = "Delete failed"))) }
 * ```
 */
fun Throwable.toMessage(fallback: String? = null): String =
    (this as? AppError)?.message ?: message ?: fallback ?: "Something went wrong"
