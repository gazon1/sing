package com.singularity.todo.feature.calendarSync.auth

import com.singularity.todo.core.auth.SecureStorage
import com.singularity.todo.core.error.runCatchingCancellable

/**
 * The revoke on the device.
 *
 * ## When this is the right revoker
 *
 * A Desktop OAuth client is a public client: Google lets it revoke a refresh token
 * without a client secret, and the refresh token is in the device's own keystore.
 * The desktop is the user's own machine, so the credential is cancelled there, at
 * Google, without a round trip to a server that never had it.
 *
 * ## Why the token is read before it is revoked
 *
 * The revoke needs the refresh token, and the refresh token is the thing the
 * credential store holds. Reading it before the store is cleared is why the order
 * in the caller matters: clear first, and the credential is gone for good — the
 * device can no longer cancel what it handed out.
 *
 * ## Why a missing token is not a failure
 *
 * An account that was never connected has nothing to revoke, and it is
 * disconnected either way. Reporting a failure for it would teach the caller
 * that "disconnect" is broken when it is exactly what the person wanted.
 */
class LocalGoogleGrantRevoker(
    private val credentialStore: GoogleCredentialStore,
    private val clientIdResolver: GoogleClientIdResolver,
    private val secureStorage: SecureStorage,
    private val tokenClientFactory: (String) -> GoogleTokenExchange,
) : GoogleGrantRevoker {

    override suspend fun revoke(userId: String): Result<Unit> = runCatchingCancellable {
        val stored = credentialStore.load(userId) ?: return@runCatchingCancellable
        val refreshToken = stored.refreshToken ?: return@runCatchingCancellable
        val clientId = clientIdResolver.resolve(secureStorage)?.clientId
            ?: throw GoogleAuthorizationException(
                "The Google client id is not configured, so the token cannot be revoked at Google",
            )
        tokenClientFactory(clientId).revoke(refreshToken)
    }
}
