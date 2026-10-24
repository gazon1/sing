package com.singularity.todo.core.auth.oauth

import io.ktor.http.decodeURLQueryComponent

/**
 * The parameters a redirect carries.
 *
 * @param code the authorization code, present when the user agreed.
 * @param state the echo of the request's state, present when the provider
 *   honours it — the check that the answer belongs to the request that asked.
 * @param error the provider's refusal code, `access_denied` when the user
 *   said no.
 * @param errorDescription the human-readable half of the refusal.
 */
data class OAuthRedirectParams(
    val code: String?,
    val state: String?,
    val error: String?,
    val errorDescription: String?,
)

/**
 * Parses the target a redirect listener received.
 *
 * The target is what the browser sent on the wire: a path, a query string, and
 * sometimes a fragment. Only the query is read — the authorization-code flow
 * puts everything there, and a fragment would mean the provider answered in
 * the implicit flow, which this project does not use.
 */
fun parseOAuthRedirect(target: String): OAuthRedirectParams {
    val query = target.substringAfter('?', "").substringBefore('#')
    val params = query
        .split('&')
        .filter { it.isNotBlank() }
        .mapNotNull { pair ->
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            if (name.isBlank()) null else name.decode() to value.decode()
        }.toMap()
    return OAuthRedirectParams(
        code = params["code"],
        state = params["state"],
        error = params["error"],
        errorDescription = params["error_description"],
    )
}

/**
 * Decodes one percent-encoded component.
 *
 * `+` is a space in a query string, but Google sends base64url values that
 * contain `+` — treating it as a space would corrupt them, so it is left alone.
 */
private fun String.decode(): String = decodeURLQueryComponent(plusIsSpace = false)
