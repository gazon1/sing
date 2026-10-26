package com.singularity.todo.core.sync

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.TransportException
import com.singularity.todo.core.error.TransportFailure
import com.singularity.todo.core.error.TransportFacts

/**
 * What a call to a database function can fail with — said in the project's words.
 *
 * ## Why these decisions are not in the seam
 *
 * `PostgrestSyncRpc` is a vendor file: it is one of the six places the Supabase
 * SDK is allowed to appear, and `VendorSdkConfinementTest` enforces that. The
 * rule it enforces is about *types*, and it is easy to satisfy it by name while
 * keeping the decisions where they were.
 *
 * The decisions are two, and both of them are the project's:
 *
 * - **that there is no project to talk to** is a state the app has to handle, not
 *   a transport failure. It gets its own code — `sync.not_configured` — because a
 *   dashboard that groups it under "the server refused" sends the reader looking
 *   at a server that is not there;
 * - **what a refused call is called** — the wording of the message, and which of
 *   the server's facts are worth repeating in it — is the project's choice, and
 *   it is the same choice for every call the sync engine makes.
 *
 * What stays in the seam is the reading: the status code, the PostgREST code and
 * the vendor's exception type. That reading is three lines and it cannot be done
 * anywhere else, because the facts live in the vendor's exception.
 *
 * The same shape as `AuthFailure` and `GoogleTokenExchangeWire`: the seam
 * transports, and the decision is the project's.
 */
internal object SyncRpcFailure {

    /**
     * There is no project configured, so the function cannot be called.
     *
     * Not a transport failure: nothing was refused, nothing was attempted. The
     * person has to enter a project first, and the message says that.
     */
    fun notConfigured(function: String): AppError = AppError.Validation(
        "No Supabase project is configured, so $function cannot be called.",
        code = "sync.not_configured",
    )

    /**
     * The call was refused, or never arrived.
     *
     * [failure] is the class the seam read off the vendor's exception; the rest
     * are the facts worth repeating, and the message repeats them in the order
     * that answers "what did the server say?" — the status first, because a 403
     * and a 500 are the same class and only the number tells them apart.
     */
    fun refused(
        failure: TransportFailure,
        facts: TransportFacts,
        function: String,
        cause: Throwable,
    ): TransportException {
        val parts = listOfNotNull(
            facts.statusCode?.toString(),
            facts.serverCode?.takeIf { it.isNotBlank() },
            facts.detail?.takeIf { it.isNotBlank() },
        )
        val said = if (parts.isEmpty()) cause::class.simpleName.orEmpty() else parts.joinToString(": ")
        return TransportException(failure, "$function failed: $said", cause)
    }
}
