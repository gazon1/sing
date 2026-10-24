package com.singularity.todo.feature.calendar_sync.auth

import com.singularity.todo.core.auth.SecureStorage
import com.singularity.todo.core.auth.oauth.base64Url
import com.singularity.todo.core.auth.oauth.generatePkcePair
import com.singularity.todo.core.auth.oauth.secureRandomBytes
import com.singularity.todo.core.error.runCatchingCancellable

/** The sign-in could not be completed, with a reason a user can act on. */
class GoogleAuthorizationException(message: String) : Exception(message)

/**
 * A sign-in that has been prepared but not yet carried out.
 *
 * The two halves travel separately on purpose: the request is safe to put in the UI
 * state — it carries the *public* half of the PKCE pair — and the verifier is not,
 * because it is the secret half.
 */
data class PendingGoogleAuthorization(
    /** What the consent has to ask for. */
    val request: GoogleAuthorizationRequest,
    /** The secret half of the PKCE pair. It never leaves the device. */
    val codeVerifier: String,
)

/**
 * What the consent has to ask for.
 */
data class GoogleAuthorizationRequest(
    /** The OAuth client id to authorize against. */
    val clientId: String,
    /** The scopes to ask for. */
    val scopes: List<String>,
    /**
     * The request's fingerprint.
     *
     * The consent sends it and the redirect has to echo it. A redirect that carries a
     * different state is not an answer to this request, and the code in it was meant
     * for somebody else.
     */
    val state: String,
    /**
     * The public half of the PKCE pair.
     *
     * Ignored where the platform builds the authorization request itself — Google's
     * authorization sheet manages its own challenge.
     */
    val codeChallenge: String,
)

/**
 * The Google sign-in, as the settings screen sees it.
 *
 * A port, so the screen can be driven by a fake: the sign-in has three outcomes the
 * user sees differently — it works, the user refuses, it fails — and only one of them
 * used to be reachable at all.
 */
interface GoogleAuthorizer {
    /**
     * The client id the sign-in would use, or null when none is configured.
     */
    suspend fun configuredClientId(): String?

    /**
     * Stores the client id the user pasted.
     *
     * @throws IllegalArgumentException when [clientId] is blank — a stored blank and a
     *   stored client id are indistinguishable at the next read.
     */
    suspend fun storeClientId(clientId: String)

    /**
     * Prepares a sign-in.
     *
     * The consent itself is not started here: it is a platform activity, and the
     * screen owns the launcher that runs it. What this prepares is everything the
     * consent needs and everything the redemption will need — the client id, the
     * scopes, the request's fingerprint, and the PKCE pair.
     */
    suspend fun beginAuthorization(): Result<PendingGoogleAuthorization>

    /**
     * Redeems the grant the consent produced and stores the credential for [userId].
     *
     * @return the credential that was stored, so the caller can report what the
     *   account is now able to do.
     */
    suspend fun redeem(
        pending: PendingGoogleAuthorization,
        grant: GoogleGrant,
        userId: String,
    ): Result<GoogleCredentials>
}

/**
 * The whole Google sign-in, in one place: what to ask for, what to redeem, and where
 * the credential goes.
 *
 * ## Why the credential is stored here and not by the caller
 *
 * A sign-in that ends with a token nobody kept is a sign-in the user has to repeat.
 * The exchange and the write belong together: the code is redeemed once, the
 * credential is written before the flow returns, and the caller learns about the
 * result afterwards.
 *
 * ## Why the client id is resolved here
 *
 * A user who pastes a client id is standing in front of a specific Google Cloud
 * project — usually their own, with their own calendars. The id baked into the
 * build belongs to whoever packaged it. The pasted id wins, because a calendar sync
 * that writes into the packager's project would be a disclosure, not a
 * misconfiguration; see [GoogleClientIdResolver].
 */
class GoogleAuthorizationService(
    private val clientIdResolver: GoogleClientIdResolver,
    private val secureStorage: SecureStorage,
    private val redeemer: GoogleGrantRedeemer,
    private val credentialStore: GoogleCredentialStore,
) : GoogleAuthorizer {

    /**
     * Prepares a sign-in.
     *
     * Fails when no client id is configured, because a consent against an empty id
     * ends in Google's "invalid client id" page — a page that names neither the app
     * nor the setting that has to change.
     */
    override suspend fun beginAuthorization(): Result<PendingGoogleAuthorization> =
        runCatchingCancellable {
            val clientId = clientIdResolver.resolve(secureStorage)?.clientId
                ?: throw GoogleAuthorizationException(
                    "No Google client id is configured. Add one in the Calendar settings.",
                )
            val pkce = generatePkcePair()
            PendingGoogleAuthorization(
                request = GoogleAuthorizationRequest(
                    clientId = clientId,
                    scopes = listOf(GOOGLE_CALENDAR_SCOPE),
                    state = secureRandomBytes(STATE_BYTES).base64Url(),
                    codeChallenge = pkce.challenge,
                ),
                codeVerifier = pkce.verifier,
            )
        }

    /**
     * Redeems the grant and stores the credential.
     *
     * The write happens before the result is returned: a sign-in that reports success
     * without a stored credential is the exact failure this feature had — the button
     * did something, the account stayed disconnected, and nothing said so.
     */
    override suspend fun redeem(
        pending: PendingGoogleAuthorization,
        grant: GoogleGrant,
        userId: String,
    ): Result<GoogleCredentials> = runCatchingCancellable {
        val credentials = redeemer.redeem(grant, pending.request.clientId, pending.codeVerifier).getOrThrow()
        credentialStore.save(userId, credentials)
        credentials
    }

    /**
     * The client id the sign-in would use, or null when none is configured.
     *
     * The pasted id wins over the build-time one: a user standing in front of a
     * specific Google Cloud project means their own project, and the packager's is
     * not it.
     */
    override suspend fun configuredClientId(): String? = clientIdResolver.resolve(secureStorage)?.clientId

    /**
     * Stores the client id the user pasted.
     *
     * @throws IllegalArgumentException when [clientId] is blank — a stored blank and a
     *   stored client id are indistinguishable at the next read.
     */
    override suspend fun storeClientId(clientId: String) {
        clientIdResolver.store(secureStorage, clientId)
    }

    companion object {
        /**
         * The scope the calendar sync needs.
         *
         * Full access rather than read-only: the sync writes the app's tasks into the
         * calendar and reads back the events it created, and a read-only grant would
         * make the second half of the feature impossible.
         */
        const val GOOGLE_CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar"
    }
}

/** 16 random bytes for the request's fingerprint — enough to be unguessable. */
private const val STATE_BYTES = 16
