package com.singularity.todo.core.error

/**
 * The kinds of failure a call to a server can have.
 *
 * ## Why four
 *
 * One code — `sync.rpc_failed`, `backup.push_failed` — was reported for all of
 * them, and the difference between the four is the difference between "sign in
 * again", "the deployment is missing a migration", "try again when there is
 * signal" and "the server said no". A person reading the first one learns
 * nothing; a dashboard grouping by it mixes four defects into one.
 *
 * The four are told apart by what the caller can do about them:
 *
 *  - [UNAUTHORIZED] — the server refused the identity. The session is gone, or
 *    the request went without one. Retrying changes nothing.
 *  - [MISSING] — the server does not have what was asked for: a function that is
 *    not in the schema cache, a bucket that was never created.
 *  - [NETWORK] — the call did not arrive, or did not come back. Worth a retry.
 *  - [REJECTED] — the server answered, and the answer was no: a row-level
 *    policy that refused the write, a request that made no sense, a server that
 *    failed. The status code is in the message, so a 403 and a 500 are told
 *    apart in the report even though they share a class.
 */
enum class TransportFailure(val codeSuffix: String, val summary: String) {
    /** The server refused the identity: HTTP 401. */
    UNAUTHORIZED(
        codeSuffix = "unauthorized",
        summary = "The server refused the request: the session is gone, or was never sent",
    ),

    /** The server does not have what was asked for: PGRST2xx, or a 404. */
    MISSING(
        codeSuffix = "not_found",
        summary = "The server does not have what was asked for",
    ),

    /** The call did not arrive, or did not come back. */
    NETWORK(
        codeSuffix = "network_failed",
        summary = "The server could not be reached",
    ),

    /** The server answered, and the answer was no. */
    REJECTED(
        codeSuffix = "rejected",
        summary = "The server refused the request",
    ),
}

/**
 * A failure that came from a call to a server, already classified.
 *
 * The classification is done at the seam, where the vendor's types are, and
 * travels with the failure as far as the code that reports it. What crosses the
 * boundary is the class and the server's own words — not the vendor's
 * exception, which would put the vendor's types back into the layers that
 * reached for them only to be told what went wrong.
 */
class TransportException(
    /** What kind of failure this was. */
    val failure: TransportFailure,
    /** The server's own words, and the status code it answered with. */
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The class of a failure, from what the server said about it.
 *
 * The decision is a pure function of three facts, and it is here rather than in
 * the seam because this is where it can be tested without a vendor: the seam's
 * job is to read those three facts off the vendor's exception, and the reading
 * is three lines long.
 *
 * @param statusCode the HTTP status the server answered with, or null when it
 *   did not answer.
 * @param serverCode the server's own code — PostgREST's `PGRST202`, for one.
 * @param answered whether the server answered at all. A response that arrived
 *   and did not parse is an answer; a socket that gave up is not.
 */
fun classifyTransportFailure(
    statusCode: Int?,
    serverCode: String?,
    answered: Boolean,
): TransportFailure = when {
    statusCode == 401 -> TransportFailure.UNAUTHORIZED
    serverCode?.startsWith(PGRST_MISSING_PREFIX) == true -> TransportFailure.MISSING
    statusCode == 404 -> TransportFailure.MISSING
    answered -> TransportFailure.REJECTED
    else -> TransportFailure.NETWORK
}

/** PostgREST's own codes for "this function is not in the schema cache". */
private const val PGRST_MISSING_PREFIX = "PGRST2"

/**
 * The error a transport failure is reported as.
 *
 * The code is the failure's own: `<area>.<class>`. The one exception is the
 * class that means "something else went wrong" — the sync engine's
 * `sync.rpc_failed`, the backup's `backup.push_failed`. Those names are already
 * in the dashboard under one group, and renaming them would split a group that
 * is one defect into four.
 */
fun transportError(
    area: String,
    failure: TransportFailure,
    message: String,
    cause: Throwable? = null,
    rejectedCode: String = "$area.${TransportFailure.REJECTED.codeSuffix}",
): AppError {
    val code = when (failure) {
        TransportFailure.UNAUTHORIZED -> "$area.${failure.codeSuffix}"
        TransportFailure.MISSING -> "$area.${failure.codeSuffix}"
        TransportFailure.NETWORK -> "$area.${failure.codeSuffix}"
        TransportFailure.REJECTED -> rejectedCode
    }
    // The summary is what the person reads; the detail is what the person pastes
    // into a report. Both are in the message, and the code is what the dashboard
    // groups by.
    val text = "${failure.summary} — $message"
    return when (failure) {
        TransportFailure.UNAUTHORIZED -> AppError.Unauthorized(text, code = code, cause = cause)
        TransportFailure.MISSING -> AppError.NotFound(text, code = code, cause = cause)
        TransportFailure.NETWORK -> AppError.Network(text, code = code, cause = cause)
        TransportFailure.REJECTED -> AppError.Rejected(text, code = code, cause = cause)
    }
}
