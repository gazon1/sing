package com.singularity.todo.core.error

sealed class AppError(message: String) : RuntimeException(message) {
    class Validation(message: String) : AppError(message)
    class NotFound(message: String) : AppError(message)
    class Unauthorized(message: String) : AppError(message)
    class Persistence(message: String) : AppError(message)
    class Network(message: String) : AppError(message)
    class Unknown(message: String) : AppError(message)
}

inline fun <T> runCatchingResult(block: () -> T): Result<T> = runCatching(block).recoverCatching { e ->
    throw when (e) {
        is AppError -> e

        else -> AppError.Unknown(
            e.message
                ?: "",
        )
    }
}
