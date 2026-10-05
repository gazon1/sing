package com.singularity.todo.core.error

/**
 * A failure the application understands well enough to name.
 *
 * Carries a [code] — a stable, dot-separated domain identifier that survives
 * refactors and message rewording — so a crash reporter can group occurrences
 * of the same defect without depending on the prose. [code] is deliberately
 * machine-shaped (`task.not_found`, `sync.push_failed`) and never contains user
 * content, because it is transmitted off-device.
 *
 * [cause] exists so the original exception survives. Before this constructor
 * took only a message, [runCatchingResult] flattened every non-`AppError` to
 * `AppError.Unknown(e.message ?: "")` and the whole cause chain was lost at
 * construction — the single worst time to lose it, since that is exactly where
 * a stack trace would have been read.
 *
 * The subtypes differ only in their default [code]; their parameters are trailing
 * and defaulted, so every existing positional construction
 * (`AppError.NotFound("Tag … no longer exists")`) keeps compiling unchanged. The
 * base [code] and [cause] carry no defaults because every subtype supplies a
 * literal code — a base default would be unreachable, and an unreachable default
 * misstates what the value actually is.
 */
sealed class AppError(message: String, val code: String, cause: Throwable?) : RuntimeException(message, cause) {

    /** Input failed validation before it reached persistence or the network. */
    class Validation(message: String, code: String = "error.validation", cause: Throwable? = null) :
        AppError(message, code, cause)

    /** A referenced entity does not exist, or the caller may not see it. */
    class NotFound(message: String, code: String = "error.not_found", cause: Throwable? = null) :
        AppError(message, code, cause)

    /** Credentials are absent, expired, or rejected. */
    class Unauthorized(message: String, code: String = "error.unauthorized", cause: Throwable? = null) :
        AppError(message, code, cause)

    /** A local read or write failed. */
    class Persistence(message: String, code: String = "error.persistence", cause: Throwable? = null) :
        AppError(message, code, cause)

    /** A remote call failed or timed out. */
    class Network(message: String, code: String = "error.network", cause: Throwable? = null) :
        AppError(message, code, cause)

    /** Unclassified. [runCatchingResult] is the only producer; it always attaches [cause]. */
    class Unknown(message: String, code: String = "error.unknown", cause: Throwable? = null) :
        AppError(message, code, cause)
}

/**
 * The throwable this error was built from, or the error itself when it was built
 * without one.
 *
 * For a crash report or a log line, this is what carries the stack trace. Handing over
 * the [AppError] instead hands over a wrapper whose own stack ends where it was
 * constructed — the sync engine's failure paths, for instance, all construct theirs in
 * a catch block, so every one of them would otherwise report the same three frames.
 */
fun AppError.original(): Throwable = cause ?: this

/**
 * This throwable as an [AppError], keeping the original as the [AppError.cause].
 *
 * An [AppError] passes through untouched — it is already named, and re-wrapping it
 * would discard the code a crash reporter groups on. Anything else becomes
 * [AppError.Unknown] with the throwable attached, so the stack trace survives; the
 * fallback when the message is empty is the class name, because `"unknown"` tells a
 * reader nothing and an empty message reaches a user as a blank error banner.
 *
 * The three places that used to inline this each did it slightly differently, and the
 * differences were the bug: the sync engine's two copies attached no cause at all, and
 * the coordinator's passed a bare `""` for a missing message.
 */
fun Throwable.toAppError(): AppError = when (this) {
    is AppError -> this

    else -> AppError.Unknown(
        message ?: this::class.simpleName.orEmpty(),
        code = "error.unknown",
        cause = this,
    )
}

/**
 * Runs [block] and captures its result, mapping any [Throwable] into an [AppError].
 *
 * A non-`AppError` throwable becomes [AppError.Unknown] **with its [Throwable] kept as
 * the cause** — the message alone loses the stack trace and the frames below it,
 * which is what makes a reported failure actionable.
 */
inline fun <T> runCatchingResult(block: () -> T): Result<T> = runCatchingCancellable(block).recoverCatching { e ->
    throw e.toAppError()
}
