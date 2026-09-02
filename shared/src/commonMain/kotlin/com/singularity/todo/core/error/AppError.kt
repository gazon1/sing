package com.singularity.todo.core.error

sealed class AppError(message: String) : RuntimeException(message) {
    class Validation(message: String) : AppError(message)
    class NotFound(message: String) : AppError(message)
    class Persistence(cause: Throwable) : AppError(cause.message ?: "Persistence error")
    class Network(cause: Throwable) : AppError(cause.message ?: "Network error")
    class Unknown(cause: Throwable) : AppError(cause.message ?: "Unknown error")
}

inline fun <T> runCatchingResult(block: () -> T): Result<T> = kotlin.runCatching(block).recoverCatching {
    throw if (it is AppError) it else AppError.Unknown(it)
}
