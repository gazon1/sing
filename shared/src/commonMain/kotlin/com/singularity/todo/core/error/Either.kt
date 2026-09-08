package com.singularity.todo.core.error

/**
 * Minimal Either type for validation errors.
 *
 * Left = error, Right = success.
 * Chosen over Arrow's Either for zero dependencies and full control.
 */
sealed interface Either<out E, out T> {
    data class Left<E>(val error: E) : Either<E, Nothing>
    data class Right<T>(val value: T) : Either<Nothing, T>

    val isLeft: Boolean get() = this is Left
    val isRight: Boolean get() = this is Right
}

/** Executes the appropriate branch based on this Either's state. */
inline fun <E, T, R> Either<E, T>.fold(left: (E) -> R, right: (T) -> R): R = when (this) {
    is Either.Left -> left(error)
    is Either.Right -> right(value)
}

/** Returns the value or a default computed from the error. */
inline fun <E, T> Either<E, T>.getOrElse(default: (E) -> T): T = when (this) {
    is Either.Left -> default(error)
    is Either.Right -> value
}

/** Maps the success value using [transform]. */
inline fun <E, T, R> Either<E, T>.map(transform: (T) -> R): Either<E, R> = when (this) {
    is Either.Left -> this
    is Either.Right -> Either.Right(transform(value))
}

/** Maps the error value using [transform]. */
inline fun <E, T, R> Either<E, T>.mapError(transform: (E) -> R): Either<R, T> = when (this) {
    is Either.Left -> Either.Left(transform(error))
    is Either.Right -> this
}

/** Converts to a standard [Result] (Left maps to Failure, Right maps to Success). */
fun <T> Either<AppError.Validation, T>.toResult(): Result<T> = when (this) {
    is Either.Left -> Result.failure(error)
    is Either.Right -> Result.success(value)
}
