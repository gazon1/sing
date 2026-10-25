package com.singularity.todo.feature.calendarSync.auth

import com.singularity.todo.core.auth.SupabaseClientProvider
import com.singularity.todo.core.error.runCatchingCancellable
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.github.jan.supabase.functions.functions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The Google token gateway over the project's own Edge Function.
 *
 * ## Why the SDK client and not a hand-built request
 *
 * The caller's identity is the Supabase session, and the session lives in the vendor
 * client. Building the request by hand would mean passing an access token around and
 * setting an `Authorization` header — the same mistake the sync transport avoids by
 * making the session the identity.
 *
 * ## Why the response is parsed here
 *
 * A renamed field reads as a null token, and a null token reads as "not connected" —
 * the failure looks like a missing grant rather than a broken contract. The parsing
 * throws instead: a response that does not carry a token is a broken response, and
 * saying so is what lets the user tell "the server is not configured" from "the grant
 * is gone".
 */
class SupabaseGoogleTokenGateway(
    private val clients: SupabaseClientProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : GoogleTokenGateway {

    override suspend fun exchange(serverAuthCode: String): Result<GoogleCredentials> =
        runCatchingCancellable {
            val body = call(
                buildJsonObject {
                    put("action", "exchange")
                    put("code", serverAuthCode)
                },
            )
            body.readCredentials()
        }

    override suspend fun accessToken(): Result<GoogleCredentials> = runCatchingCancellable {
        val body = call(buildJsonObject { put("action", "token") })
        body.readCredentials()
    }

    override suspend fun revoke(): Result<Unit> = runCatchingCancellable {
        val response = invoke(buildJsonObject { put("action", "revoke") })
        // 404 is the server saying it holds no grant for this account: nothing to
        // revoke, and the account is disconnected either way. It is not a failure.
        if (response.status == HttpStatusCode.NotFound) return@runCatchingCancellable
        if (response.status != HttpStatusCode.OK) {
            throw GoogleAuthorizationException(
                "The server refused to revoke the Google grant (HTTP ${response.status.value})",
            )
        }
    }

    private suspend fun call(payload: JsonObject): JsonObject {
        val response = invoke(payload)
        val text: String = response.bodyAsText()
        return json.parseToJsonElement(text).jsonObject
    }

    private suspend fun invoke(payload: JsonObject): HttpResponse {
        val client = clients.client() ?: throw GoogleAuthorizationException(
            "No cloud server is configured. Add your Supabase project in the Account settings first.",
        )
        // The body is the encoded text, not a `JsonObject`: the SDK's client is not
        // necessarily set up with a JSON content negotiator, and a body that cannot be
        // serialized fails at the *request*, where the message says nothing about which
        // of the two halves is wrong.
        return client.functions.invoke(FUNCTION_NAME) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), payload))
        }
    }

    private fun JsonObject.readCredentials(): GoogleCredentials = readGoogleCredentials()

    private companion object {
        /** The Edge Function that redeems the code and holds the refresh token. */
        const val FUNCTION_NAME = "google-oauth-exchange"
    }
}

/**
 * Reads the server's answer.
 *
 * ## Why this is a function and not a line inside the request
 *
 * The wire format is the thing that can go wrong silently: a renamed field reads as
 * a null token, and a null token reads as "not connected" — a failure that looks like
 * a missing grant rather than a broken contract. Kept separate, it can be checked
 * against a canned body, with no network and no server.
 *
 * ## Why a missing token throws
 *
 * Because the alternative is a credential with an empty access token, and the first
 * thing that happens with that is a `401` from Google, which says nothing about the
 * server that answered.
 */
internal fun JsonObject.readGoogleCredentials(): GoogleCredentials {
    val token = this["access_token"]?.jsonPrimitive?.content
    if (token.isNullOrBlank()) {
        throw GoogleAuthorizationException("The server did not return a Google access token")
    }
    // The refresh token stays on the server: it is the server's credential, and a
    // device that holds it would have to renew it with the client secret.
    return GoogleCredentials(
        accessToken = token,
        refreshToken = null,
        expiresAtEpochMs = this["expires_at_ms"]?.jsonPrimitive?.longOrNull
            ?: System.currentTimeMillis() + DEFAULT_GOOGLE_TOKEN_EXPIRY_MS,
    )
}

/**
 * How long an access token is assumed to last when the server does not say.
 *
 * Google's own figure is an hour. Assuming less would make the app refresh more often
 * than it has to; assuming more would use a token that is dead.
 */
internal const val DEFAULT_GOOGLE_TOKEN_EXPIRY_MS = 55 * 60 * 1000L
