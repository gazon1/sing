package com.singularity.todo.feature.calendarSync.auth

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The wire contract with the project's own Edge Function.
 *
 * ## Why these decisions live here and not in the vendor's file
 *
 * The Edge Function, the payload it accepts and the answer it gives are *this
 * project's* decisions — the server side is in this repository, under
 * `supabase/functions/google-oauth-exchange`. The vendor's SDK is only the
 * transport that carries them.
 *
 * Keeping the contract in a file of the project's own means:
 *
 * - it can be read without reading a file that imports the SDK, and it can be
 *   checked without a network, an SDK or a device — which is what the tests in
 *   `SupabaseGoogleTokenGatewayTest` do;
 * - the vendor's file holds what belongs to the vendor: the call, and the session
 *   the call is made with. Everything else is a decision that outlives the SDK.
 *
 * ## The three requests
 *
 * One function, three actions. The action says which of the three things the
 * person asked for this is: redeem the code, hand out the current token, or
 * revoke the grant. Splitting them into three functions would mean three
 * deployments to keep in step, and the difference between them is one word.
 */
internal object GoogleTokenExchangeWire {

    /** The Edge Function that redeems the code and holds the refresh token. */
    const val FUNCTION_NAME = "google-oauth-exchange"

    /**
     * The request that redeems an authorization code.
     *
     * The code is the one-time answer Google gave the browser. It travels to the
     * server once, and the server trades it for a token it keeps — the device
     * never sees the refresh token.
     */
    fun exchangeRequest(code: String): JsonObject = buildJsonObject {
        put("action", "exchange")
        put("code", code)
    }

    /**
     * The request that asks for the token the server already holds.
     *
     * No code travels: the server knows which account it is talking about, from
     * the session the call is made with.
     */
    fun tokenRequest(): JsonObject = buildJsonObject {
        put("action", "token")
    }

    /** The request that cancels the grant on the server. */
    fun revokeRequest(): JsonObject = buildJsonObject {
        put("action", "revoke")
    }

    /**
     * Whether the server's answer to a revoke means the grant is gone.
     *
     * A `404` is the server saying it holds no grant for this account: there is
     * nothing to revoke, and the account is disconnected either way. It is not a
     * failure, and a person who pressed "Disconnect" must not be told that
     * something went wrong.
     *
     * Everything else that is not a `200` is the server refusing.
     */
    fun revokeIsDone(status: Int): Boolean = status == HTTP_OK || status == HTTP_NOT_FOUND

    /**
     * What to say when no cloud server is configured.
     *
     * "Add your Supabase project in the Account settings first" is better advice
     * than "the server refused the request", and it is the same advice whichever
     * call was refused.
     */
    fun noCloudServerConfigured(): GoogleAuthorizationException = GoogleAuthorizationException(
        "No cloud server is configured. Add your Supabase project in the Account settings first.",
    )

    private const val HTTP_OK = 200
    private const val HTTP_NOT_FOUND = 404
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
