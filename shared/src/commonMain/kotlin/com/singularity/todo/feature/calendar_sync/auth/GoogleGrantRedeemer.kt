package com.singularity.todo.feature.calendar_sync.auth

/**
 * Turns the grant the consent produced into a credential.
 *
 * ## Why this is a port
 *
 * The two platforms redeem different things in different places, and neither of them
 * belongs in the orchestration:
 *
 * - **Desktop** redeems the authorization code itself, against Google's token endpoint.
 *   A Desktop OAuth client is a public client, so the exchange needs no secret — the
 *   device can do it, and the refresh token lands in the device's own keystore.
 * - **Android** redeems the server auth code on the *server*, which holds the client
 *   secret. The device never sees a refresh token at all.
 *
 * The orchestration — which client id, which scopes, what to store — is the same on
 * both, and it is in [GoogleAuthorizationService].
 */
interface GoogleGrantRedeemer {

    /**
     * Redeems [grant] for a credential.
     *
     * @param clientId the OAuth client id the grant was made against. The device-side
     *   exchange needs it; the server-side one knows its own.
     * @param codeVerifier the PKCE verifier sent on the authorization request, when one
     *   was used. The offline flow does not use PKCE — the server auth code is bound to
     *   the client instead — so a platform that redeems on the server ignores it.
     */
    suspend fun redeem(
        grant: GoogleGrant,
        clientId: String,
        codeVerifier: String?,
    ): Result<GoogleCredentials>
}
