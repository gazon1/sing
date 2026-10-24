package com.singularity.todo.feature.calendar_sync.auth

import com.singularity.todo.core.auth.SecureStorage
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.error.runCatchingCancellable

/**
 * The access token, renewed on the device.
 *
 * ## When this is the right provider
 *
 * A Desktop OAuth client is a public client: Google lets it renew a refresh token
 * without a client secret. The refresh token is in the device's own keystore, and
 * the desktop is the user's own machine, so renewing it there is the right place.
 *
 * ## What happens when the renewal fails
 *
 * The grant is gone — the user revoked it, or the refresh token expired. The stored
 * credential is cleared, because a credential that cannot be renewed is a credential
 * that will fail every call from now on, and the user should be asked to connect
 * again rather than to watch a sync fail with a token nobody can explain.
 */
class LocalGoogleAccessTokenProvider(
    private val credentialStore: GoogleCredentialStore,
    private val clientIdResolver: GoogleClientIdResolver,
    private val secureStorage: SecureStorage,
    private val tokenClientFactory: (String) -> GoogleTokenExchange,
) : GoogleAccessTokenProvider {

    override suspend fun accessToken(userId: UserId): String? {
        val stored = credentialStore.load(userId.value)
        if (stored == null) return null
        if (!stored.isExpired) return stored.accessToken

        val refreshToken = stored.refreshToken
        val clientId = clientIdResolver.resolve(secureStorage)?.clientId
        if (refreshToken == null || clientId == null) return null
        val renewed = runCatchingCancellable {
            tokenClientFactory(clientId).refresh(refreshToken)
        }.getOrNull()

        return if (renewed == null) {
            // The grant is gone. Clearing it turns "every call fails" into "the
            // account is disconnected", which is a state the user can act on.
            credentialStore.clear(userId.value)
            null
        } else {
            credentialStore.save(userId.value, renewed)
            renewed.accessToken
        }
    }
}
