package com.singularity.todo.core.auth

import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [SessionStore] that keeps tokens in the platform keychain instead of in
 * preferences.
 *
 * ## What was wrong before
 *
 * [DataStoreSessionStore] wrote the access token, the refresh token and the
 * account's email address into `DataStore<Preferences>` — a plain-text file in
 * the app's data directory. The refresh token is a bearer credential for the
 * user's account: anyone who can read that file can mint an access token and act
 * as the user, and on a rooted device or a stolen backup that is a file read.
 *
 * ## The migration, and its one ordering rule
 *
 * An install that already has a token in preferences has to move it once. The
 * order is **write to the keychain first, erase from preferences second**, and it
 * is the whole design: the reverse order has a window in which a crash, a
 * battery pull or a force-stop leaves the user signed out of their own account
 * with no way back, because the token was in the place being emptied and was
 * already gone from the one being filled. Doing it this way means a crash at any
 * point leaves the token in at least one place, and the next launch retries.
 *
 * A failed write therefore does **not** erase. The plaintext copy stays, and the
 * next attempt tries again — which is the correct outcome, because a plaintext
 * token is a problem but a missing token is a user who has lost their account.
 *
 * ## Why the migration is not done in the constructor
 *
 * Storage is suspending and can fail, and this class is built in the Koin graph
 * before any screen exists. Doing it lazily, on the first read of a token, means
 * the work happens where a failure has somewhere to go, and a user who never
 * reaches a screen that needs the token never pays for it.
 */
class SecureSessionStore(
    private val log: Logger,
    private val secure: SecureStorage,
    /**
     * Where a pre-upgrade token may still sit. Read-only in practice: this class
     * only ever *removes* from it, and only after the value is safely stored.
     */
    private val legacy: DataStoreSessionStore,
) : SessionStore {

    private val migration = Mutex()
    private var attempted = false

    private val _accessToken = MutableStateFlow<String?>(null)
    private val _refreshToken = MutableStateFlow<String?>(null)
    private val _userEmail = MutableStateFlow<String?>(null)

    override val accessToken: Flow<String?> = _accessToken.asStateFlow()
    override val refreshToken: Flow<String?> = _refreshToken.asStateFlow()
    override val userEmail: Flow<String?> = _userEmail.asStateFlow()

    /**
     * The device id is not a secret and does not belong in a keychain: it is an
     * identifier the server uses to tell installs apart, it has no authority on
     * its own, and putting it in hardware-backed storage would mean a factory
     * reset of the keyring silently changes it. It stays in preferences.
     */
    override val deviceId: StateFlow<String> get() = legacy.deviceId

    override suspend fun getOrInitDeviceId(): String = legacy.getOrInitDeviceId()

    /**
     * Writes the session, or nothing.
     *
     * The four fields are written one at a time because the keychain has no
     * multi-key transaction, and a failure on the third left a half-written identity
     * in the store: two fields of the new session beside two fields of the old one,
     * or of none. A later launch reads the tokens it finds and concludes the device
     * is signed in as an owner the fields disagree about, which is worse than being
     * signed out — the user is told they are signed in, and every row they then write
     * is attributed to an owner the store half-records.
     *
     * So the write is undone when it does not finish. That is the opposite of the
     * migration's rule below, and for the same reason inverted: there, a failure means
     * the *only* copy is in plain text and must be kept; here, the tokens being
     * written are the fresh ones and the plain-text copy is not involved, so a partial
     * set is the worst outcome available and there is nothing worth trading it for.
     */
    override suspend fun save(session: Session.SignedIn) {
        // Suppress the migration for this write: the caller has just handed us a
        // current token, and moving a stale one first would overwrite it.
        attempted = true
        runCatchingCancellable {
            secure.write(KEY_ACCESS, session.accessToken)
            secure.write(KEY_REFRESH, session.refreshToken)
            secure.write(KEY_EMAIL, session.email)
            secure.write(KEY_USER_ID, session.userId.value)
        }.onFailure { cause ->
            // Undo, and report the original failure rather than anything the undo
            // might throw: the cause is what tells the caller the keychain is the
            // problem, and an undo that fails must not replace that with a second,
            // different error.
            runCatchingCancellable { deleteSession() }
                .onFailure { log.w(it) { "Could not undo a partially written session" } }
            log.e(cause) { "Could not write the session; the store was left without one" }
            throw AppError.Persistence(
                // Names the local failure and, deliberately, does not suggest the
                // address or the password was at fault — it was neither, and a user
                // told their password is wrong will retype it. It also says the
                // attempt got as far as the provider, because for a sign-up the
                // account now exists and a blind retry would answer "that address is
                // already registered".
                "This device could not save your session, so you are not signed in. " +
                    "If this was a new account it still exists — check your email.",
                code = STORAGE_FAILED,
                cause = cause,
            )
        }
        // A previously migrated install may still have the old copies if an
        // earlier version wrote them after a sign-in. Removing is idempotent.
        legacy.forgetTokens()
        publish()
    }

    private suspend fun deleteSession() {
        secure.delete(KEY_ACCESS)
        secure.delete(KEY_REFRESH)
        secure.delete(KEY_EMAIL)
        secure.delete(KEY_USER_ID)
    }

    override suspend fun saveDeviceId(id: String) = legacy.saveDeviceId(id)

    override suspend fun clear() {
        deleteSession()
        legacy.forgetTokens()
        publish()
    }

    private suspend fun migrateOnce() {
        if (attempted) return
        migration.withLock {
            if (attempted) return
            attempted = true
            val legacyAccess = legacy.accessToken.first()
            val legacyRefresh = legacy.refreshToken.first()
            val legacyEmail = legacy.userEmail.first()

            if (legacyAccess == null && legacyRefresh == null && legacyEmail == null) {
                publish()
                return
            }

            // A session is already in the secure store, so the plain-text copy is an
            // older one that a write did not manage to erase before the process ended.
            // Importing it would move this device *backwards* — a refresh token that
            // was rotated on the way out is usually already revoked, so the result is
            // a sign-in that works once and then fails, and the user has been signed
            // out of a session that was sitting in the keychain the whole time. The
            // newer session wins and the leftover is erased.
            if (secure.read(KEY_ACCESS) != null) {
                log.w { "Discarding a plain-text session the secure store has already superseded" }
                legacy.forgetTokens()
                publish()
                return
            }

            // Any one of these throwing leaves the plaintext in place, and the
            // `attempted` flag is already set, so this install keeps the copy it
            // has rather than losing the session over a transient keychain error.
            // A permanent keyring failure then looks like a signed-out user, which
            // is recoverable; the alternative is a silent sign-out with no copy
            // anywhere.
            runCatchingCancellable {
                if (legacyAccess != null) secure.write(KEY_ACCESS, legacyAccess)
                if (legacyRefresh != null) secure.write(KEY_REFRESH, legacyRefresh)
                if (legacyEmail != null) secure.write(KEY_EMAIL, legacyEmail)
            }
                .onSuccess { legacy.forgetTokens() }
                .onFailure {
                    log.w(it) { "Could not move the session token into secure storage; the plain-text copy is kept" }
                }
            publish()
        }
    }

    private suspend fun publish() {
        _accessToken.value = secure.read(KEY_ACCESS)
        _refreshToken.value = secure.read(KEY_REFRESH)
        _userEmail.value = secure.read(KEY_EMAIL)
    }

    /**
     * The access token, migrating an old plain-text one across first.
     *
     * A value rather than a flow, because the caller hands it to the SDK once.
     * Null after a failed migration is indistinguishable from "signed out", and
     * that asymmetry is deliberate: the failure mode of a failed secure write is
     * that the user signs in again, never that they keep a token the server has
     * already revoked.
     */
    suspend fun currentAccessToken(): String? {
        migrateOnce()
        return secure.read(KEY_ACCESS)
    }

    /**
     * The refresh token, or null when the session is anonymous.
     *
     * A separate reader rather than a flow because the restore path needs all
     * three values at one moment: reading three flows independently can observe
     * a half-written session, and would then persist a mixture of two.
     */
    suspend fun currentRefreshToken(): String? {
        migrateOnce()
        return secure.read(KEY_REFRESH)
    }

    /** The owner id stored beside the tokens, or null when there is no session. */
    suspend fun currentUserId(): String? {
        migrateOnce()
        return secure.read(KEY_USER_ID)
    }

    /** The stored email, or null. Part of the same snapshot as the tokens. */
    suspend fun currentEmail(): String? {
        migrateOnce()
        return secure.read(KEY_EMAIL)
    }

    companion object {
        /**
         * The code a failed session write reports under.
         *
         * Separate from the generic persistence code because this one has a specific
         * consequence the caller must act on: the provider has already issued a
         * session, and a retry would be a second sign-in rather than a repair.
         */
        const val STORAGE_FAILED = "auth.session_not_stored"

        /**
         * Secure-store keys. Not the tokens themselves — the values under them
         * are, and that is the whole reason this class exists.
         */
        const val KEY_ACCESS = "auth.access_token"
        const val KEY_REFRESH = "auth.refresh_token"
        const val KEY_EMAIL = "auth.user_email"

        /**
         * The owner id, stored beside the tokens.
         *
         * Not derivable from them: a JWT's subject is, but decoding it here to
         * recover an id the client already knows would mean trusting a token to
         * tell the client who it is, on a path where the token may be expired and
         * unverifiable. The alternative — publishing a session whose owner is
         * unknown, or inventing a placeholder — puts a wrong id in front of the
         * sync scope provider, and every row it then writes is attributed to an
         * owner that does not exist.
         */
        const val KEY_USER_ID = "auth.user_id"
    }
}
