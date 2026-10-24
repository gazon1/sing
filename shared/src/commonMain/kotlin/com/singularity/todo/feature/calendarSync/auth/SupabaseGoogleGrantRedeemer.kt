package com.singularity.todo.feature.calendarSync.auth

/**
 * Redeems the grant on the server.
 *
 * The counterpart of [LocalGoogleGrantRedeemer]: on this platform the code can only
 * be redeemed by whoever holds the client secret, and that is the server.
 */
class SupabaseGoogleGrantRedeemer(private val gateway: GoogleTokenGateway) : GoogleGrantRedeemer {

    override suspend fun redeem(
        grant: GoogleGrant,
        clientId: String,
        codeVerifier: String?,
    ): Result<GoogleCredentials> = when (grant) {
        is GoogleGrant.ServerAuthCode -> gateway.exchange(grant.code)

        is GoogleGrant.AuthorizationCode -> Result.failure(
            GoogleAuthorizationException(
                "An authorization code cannot be redeemed on this platform: " +
                    "the exchange needs the client secret, which lives on the server",
            ),
        )
    }
}
