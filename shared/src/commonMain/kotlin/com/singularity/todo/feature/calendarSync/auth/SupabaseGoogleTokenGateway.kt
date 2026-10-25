package com.singularity.todo.feature.calendarSync.auth

import com.singularity.todo.core.auth.SupabaseClientProvider
import com.singularity.todo.core.error.runCatchingCancellable
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.github.jan.supabase.functions.functions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The Google token gateway over the project's own Edge Function.
 *
 * ## What this file holds, and what it does not
 *
 * This is the seam: the vendor's client, the call, and the session the call is made
 * with. Everything else — the function's name, the three requests, the shape of the
 * answer, which status codes mean "the grant is gone" — is a decision of *this*
 * project, and it lives in [GoogleTokenExchangeWire], where it can be read and checked
 * without an SDK.
 *
 * ## Why the SDK client and not a hand-built request
 *
 * The caller's identity is the Supabase session, and the session lives in the vendor
 * client. Building the request by hand would mean passing an access token around and
 * setting an `Authorization` header — the same mistake the sync transport avoids by
 * making the session the identity.
 */
class SupabaseGoogleTokenGateway(
    private val clients: SupabaseClientProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : GoogleTokenGateway {

    override suspend fun exchange(serverAuthCode: String): Result<GoogleCredentials> =
        runCatchingCancellable {
            val body = call(GoogleTokenExchangeWire.exchangeRequest(serverAuthCode))
            body.readGoogleCredentials()
        }

    override suspend fun accessToken(): Result<GoogleCredentials> = runCatchingCancellable {
        val body = call(GoogleTokenExchangeWire.tokenRequest())
        body.readGoogleCredentials()
    }

    override suspend fun revoke(): Result<Unit> = runCatchingCancellable {
        val response = invoke(GoogleTokenExchangeWire.revokeRequest())
        // Whether the answer means "the grant is gone" is the contract's rule, not
        // this file's: a 404 is the server saying it holds no grant for this
        // account, which is not a failure.
        if (!GoogleTokenExchangeWire.revokeIsDone(response.status.value)) {
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
        val client = clients.client() ?: throw GoogleTokenExchangeWire.noCloudServerConfigured()
        // The body is the encoded text, not a `JsonObject`: the SDK's client is not
        // necessarily set up with a JSON content negotiator, and a body that cannot be
        // serialized fails at the *request*, where the message says nothing about which
        // of the two halves is wrong.
        return client.functions.invoke(GoogleTokenExchangeWire.FUNCTION_NAME) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), payload))
        }
    }
}
