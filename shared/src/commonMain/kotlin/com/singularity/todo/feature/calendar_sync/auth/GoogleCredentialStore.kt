package com.singularity.todo.feature.calendar_sync.auth

import com.singularity.todo.core.auth.SecureStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The Google credentials, in the shape a background sync needs.
 *
 * @param accessToken short-lived; usable until [expiresAt].
 * @param refreshToken the durable half — absent when the grant was made without
 *   `access_type=offline`, which is the one case where background sync cannot work.
 * @param expiresAt when [accessToken] stops being valid.
 */
data class GoogleCredentials(val accessToken: String, val refreshToken: String?, val expiresAtEpochMs: Long) {
    /**
     * Whether this grant can outlive a single request.
     *
     * A credential with no refresh token is still usable — for the foreground sync that
     * follows a sign-in — but it cannot be renewed, so the next background run will fail.
     * Callers should check this rather than discovering it an hour later.
     */
    val canRenew: Boolean get() = !refreshToken.isNullOrBlank()
}

/**
 * Where Google credentials live between runs.
 *
 * ## Why this is an interface and not a direct [SecureStorage] call
 *
 * Two reasons, and the first is the design. The whole point of the hybrid OAuth decision
 * is that KMPAuth handles consent while *this* project owns the durable credential — so the
 * store for that credential is a first-class thing with its own rules (key prefixing,
 * clearing on sign-out, refusing to persist a grant it cannot renew), not a string key
 * someone picks. The second is that a test can then assert the rules without a keystore.
 *
 * ## Why the values are stored separately
 *
 * Splitting the fields means a corrupt or partial write can invalidate the access token
 * without destroying the refresh token. Packing them into one blob means one bad write
 * costs the user a re-consent, and re-consent is a browser, a login, and a permission
 * screen — the most expensive failure this feature has.
 */
interface GoogleCredentialStore {

    /**
     * The credential for [userId], or null when this profile has never authorised.
     *
     * Returns null rather than throwing for a missing profile: "not connected" is an
     * ordinary state the settings screen renders, not an error.
     */
    suspend fun load(userId: String): GoogleCredentials?

    /** Persists [credentials] for [userId], replacing anything already there. */
    suspend fun save(userId: String, credentials: GoogleCredentials)

    /**
     * Forgets [userId]'s credential.
     *
     * Called on disconnect and on sign-out. KMPAuth's own `signOut` is a no-op on desktop,
     * so this is the only thing standing between "disconnect" and credentials that are
     * still on disk — see the OAuth ADR.
     */
    suspend fun clear(userId: String)
}

/** Key prefix, so a Google credential can never collide with another secret's key. */
internal fun googleCredentialKey(userId: String): String = "google_calendar:$userId"

/**
 * Secure-storage-backed [GoogleCredentialStore].
 *
 * The refresh token lands in the platform keystore on Android (EncryptedSharedPreferences)
 * and in libsecret on desktop, which is the point of doing the token exchange here rather
 * than accepting the library's own storage.
 *
 * Built on [SecureStorage] — the narrow three-method slice — rather than
 * [com.singularity.todo.core.security.SecureStoragePort], so a test can supply a map
 * without implementing a keyring. The wider port adds `isHardwareBacked()`, which is worth
 * surfacing in settings at some point but is not needed to store a token correctly.
 */
class SecureStorageGoogleCredentialStore(
    private val secureStorage: SecureStorage,
    private val json: Json = DEFAULT_JSON,
) : GoogleCredentialStore {

    override suspend fun load(userId: String): GoogleCredentials? {
        val access = secureStorage.read(googleCredentialKey(userId) + ":access") ?: return null
        val refresh = secureStorage.read(googleCredentialKey(userId) + ":refresh")
        val expiry = secureStorage.read(googleCredentialKey(userId) + ":expires") ?: return null
        return GoogleCredentials(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochMs = expiry.toLongOrNull() ?: return null,
        )
    }

    override suspend fun save(userId: String, credentials: GoogleCredentials) {
        // A credential with no refresh token is still written: it serves the foreground
        // sync that follows sign-in, and the UI can say "this will need re-authorising"
        // because `canRenew` is false. Discarding it would make a one-off foreground
        // session impossible.
        secureStorage.write(googleCredentialKey(userId) + ":access", credentials.accessToken)
        secureStorage.write(googleCredentialKey(userId) + ":expires", credentials.expiresAtEpochMs.toString())
        val existingRefresh = secureStorage.read(googleCredentialKey(userId) + ":refresh")
        val newRefresh = credentials.refreshToken
        when {
            // Never overwrite a good refresh token with null. A later save that carries
            // only an access token must not cost the user their durable grant.
            !newRefresh.isNullOrBlank() -> secureStorage.write(googleCredentialKey(userId) + ":refresh", newRefresh)

            existingRefresh == null -> secureStorage.delete(googleCredentialKey(userId) + ":refresh")

            else -> Unit // keep what we have
        }
    }

    override suspend fun clear(userId: String) {
        secureStorage.delete(googleCredentialKey(userId) + ":access")
        secureStorage.delete(googleCredentialKey(userId) + ":refresh")
        secureStorage.delete(googleCredentialKey(userId) + ":expires")
    }

    private companion object {
        val DEFAULT_JSON = Json { ignoreUnknownKeys = true }
    }
}

/** Parses Google's token-endpoint response into a [GoogleCredentials]. */
internal fun parseTokenResponse(body: String): GoogleCredentials? = runCatching {
    val obj = Json.parseToJsonElement(body).jsonObject
    val access = obj["access_token"]?.asPrimitive() ?: return null
    // Google's `expires_in` is seconds; a response without it is malformed rather than
    // infinite, and treating it as "never expires" would hide a broken grant.
    val expiresIn = obj["expires_in"]?.asPrimitive()?.toLongOrNull() ?: return null
    GoogleCredentials(
        accessToken = access,
        // Absent on a refresh response, which is normal: Google only returns a new refresh
        // token on a fresh grant, not when redeeming an existing one.
        refreshToken = obj["refresh_token"]?.asPrimitive(),
        expiresAtEpochMs = System.currentTimeMillis() + expiresIn * 1_000,
    )
}.getOrNull()

private fun kotlinx.serialization.json.JsonElement.asPrimitive(): String? =
    (this as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content
