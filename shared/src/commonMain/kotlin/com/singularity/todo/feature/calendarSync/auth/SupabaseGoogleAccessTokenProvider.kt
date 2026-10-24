package com.singularity.todo.feature.calendarSync.auth

import com.singularity.todo.core.ids.UserId

/**
 * The access token, renewed on the server.
 *
 * ## Why the device does not renew it
 *
 * The refresh token behind the grant lives next to the Google client secret, on the
 * server. Renewing it needs that secret, and a secret inside an APK is a secret that
 * has leaked. So the device asks the server for a token and the server does the part
 * that needs a secret.
 *
 * ## What happens when the server cannot be reached
 *
 * The stored token is used if it is still alive. A sync that fails because the
 * network was down is recoverable; a sync that fails because the app gave up on a
 * token it had is not — and the token it had is exactly what the fallback keeps.
 */
class SupabaseGoogleAccessTokenProvider(
    private val gateway: GoogleTokenGateway,
    private val credentialStore: GoogleCredentialStore,
) : GoogleAccessTokenProvider {

    override suspend fun accessToken(userId: UserId): String? {
        val fresh = gateway.accessToken().getOrNull()
        if (fresh != null) {
            credentialStore.save(userId.value, fresh)
            return fresh.accessToken
        }
        val stored = credentialStore.load(userId.value) ?: return null
        return if (stored.isExpired) null else stored.accessToken
    }
}
