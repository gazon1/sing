package com.singularity.todo.core.transport

import com.singularity.todo.core.error.TransportException
import com.singularity.todo.core.error.TransportFailure
import com.singularity.todo.core.error.TransportFacts
import com.singularity.todo.core.error.classifyTransportFailure
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.exceptions.SupabaseEncodingException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException

/**
 * Supabase's failures, told apart.
 *
 * ## Why this file exists
 *
 * The SDK reports a refused identity, a missing function, a lost connection
 * and a server that said no as four different exception types — and the layers
 * above the seam saw one thing: "the call failed". That is what made the
 * difference between "the anon key went instead of the user's token" and "the
 * row-level policy refused the write" invisible in the logs: the first is a
 * session problem, the second is a data problem, and they arrived as the same
 * line.
 *
 * The classification happens here, in the one place that is allowed to name
 * the vendor, and what leaves is the class and the server's own words. The
 * layers above read a project type and never learn the SDK's.
 *
 * ## Why the status code leads
 *
 * The status code is the one thing every failure has and every failure agrees
 * on. PostgREST's own code (`PGRST202` — "could not find the function in the
 * schema cache") refines it: a 404 that carries `PGRST2xx` is a deployment
 * that is missing a migration, and a 404 without one is a path that is not
 * there. Both are "the server does not have it", and the message says which.
 */
internal object SupabaseTransportFailure {

    /**
     * The class of a vendor failure.
     *
     * An exception that is not the SDK's is a call that did not complete, and
     * that is the network class: the vendor's own types cover everything the
     * server said, and what is left is the socket.
     */
    fun classify(error: Throwable): TransportFailure {
        val status = (error as? RestException)?.statusCode
        val postgrestCode: String? = (error as? PostgrestRestException)?.code
        return classifyTransportFailure(
            statusCode = status,
            serverCode = postgrestCode,
            answered = status != null || error is SupabaseEncodingException,
        )
    }

    /**
     * The facts the server's answer carries.
     *
     * The status code, PostgREST's own code and the server's words live in the
     * vendor's exception, so this is where they are read — and they leave as
     * three plain values, without a vendor's type attached. What is done with
     * them is the project's decision, and it is made further up.
     */
    fun facts(error: Throwable): TransportFacts = TransportFacts(
        statusCode = (error as? RestException)?.statusCode,
        // PostgREST has its own code and the rest of the SDK carries the server's
        // own error string — the same fallback the exception path uses, because a
        // storage or auth failure that loses its code is a failure nobody can act on.
        serverCode = (error as? PostgrestRestException)?.code ?: (error as? RestException)?.error,
        detail = (error as? RestException)?.description ?: error.message,
    )

    /**
     * The failure as the project sees it: the class, and the server's words.
     *
     * The status code is in the message on purpose. A 403 and a 500 are the
     * same class, and without the number a reader could not tell a row-level
     * policy that refused a write from a server that failed — which is the
     * difference between a bug in the app and a bug in the deployment.
     */
    fun toTransportException(error: Throwable, what: String): TransportException {
        val rest = error as? RestException
        val postgrestCode: String? = (error as? PostgrestRestException)?.code
        val code = postgrestCode ?: rest?.error
        val detail = rest?.description ?: error.message
        val status = rest?.statusCode
        val parts = listOfNotNull(
            status?.toString(),
            code?.takeIf { it.isNotBlank() },
            detail?.takeIf { it.isNotBlank() },
        )
        val said = if (parts.isEmpty()) error::class.simpleName.orEmpty() else parts.joinToString(": ")
        return TransportException(classify(error), "$what: $said", error)
    }
}
