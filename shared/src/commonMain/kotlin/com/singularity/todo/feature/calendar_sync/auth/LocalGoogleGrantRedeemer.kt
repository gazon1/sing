package com.singularity.todo.feature.calendar_sync.auth

import com.singularity.todo.core.error.runCatchingCancellable

/**
 * Redeems the grant on the device.
 *
 * ## When this is the right redeemer
 *
 * A Desktop OAuth client is a *public client*: Google lets it exchange a code without
 * a client secret, and lets it renew a refresh token the same way. So on the desktop
 * the device can do the whole exchange, and the refresh token ends up in the device's
 * own keystore, where it belongs — the desktop is the user's own machine.
 *
 * ## Why this is not the Android path
 *
 * An Android OAuth client is not a public client in the same sense: the offline flow
 * hands the app a *server auth code*, and redeeming it needs the client secret. A
 * client secret inside an APK is a secret that has leaked, so on Android the
 * redemption happens on the server — see [SupabaseGoogleGrantRedeemer].
 */
class LocalGoogleGrantRedeemer(private val tokenClientFactory: (String) -> GoogleTokenExchange) : GoogleGrantRedeemer {

    override suspend fun redeem(
        grant: GoogleGrant,
        clientId: String,
        codeVerifier: String?,
    ): Result<GoogleCredentials> = when (grant) {
        is GoogleGrant.AuthorizationCode -> runCatchingCancellable {
            tokenClientFactory(clientId).exchangeCode(
                code = grant.code,
                redirectUri = grant.redirectUri,
                codeVerifier = codeVerifier,
            )
        }

        is GoogleGrant.ServerAuthCode -> Result.failure(
            GoogleAuthorizationException(
                "A server auth code can only be redeemed by the server that holds the client secret",
            ),
        )
    }
}
