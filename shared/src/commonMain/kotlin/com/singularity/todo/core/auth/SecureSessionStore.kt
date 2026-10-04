package com.singularity.todo.core.auth

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

    override suspend fun save(session: Session.SignedIn) {
        // Suppress the migration for this write: the caller has just handed us a
        // current token, and moving a stale one first would overwrite it.
        attempted = true
        secure.write(KEY_ACCESS, session.accessToken)
        secure.write(KEY_REFRESH, session.refreshToken)
        secure.write(KEY_EMAIL, session.email)
        // A previously migrated install may still have the old copies if an
        // earlier version wrote them after a sign-in. Removing is idempotent.
        legacy.forgetTokens()
        publish()
    }

    override suspend fun saveDeviceId(id: String) = legacy.saveDeviceId(id)

    override suspend fun clear() {
        secure.delete(KEY_ACCESS)
        secure.delete(KEY_REFRESH)
        secure.delete(KEY_EMAIL)
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
            // Any one of these throwing leaves the plaintext in place, and the
            // `attempted` flag is already set, so this install keeps the copy it
            // has rather than losing the session over a transient keychain error.
            // A permanent keyring failure then looks like a signed-out user, which
            // is recoverable; the alternative is a silent sign-out with no copy
            // anywhere.
            runCatching { writeAll(legacyAccess, legacyRefresh, legacyEmail) }
                .onSuccess { legacy.forgetTokens() }
            publish()
        }
    }

    private suspend fun writeAll(access: String?, refresh: String?, email: String?) {
        if (access != null) secure.write(KEY_ACCESS, access)
        if (refresh != null) secure.write(KEY_REFRESH, refresh)
        if (email != null) secure.write(KEY_EMAIL, email)
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

    companion object {
        /**
         * Secure-store keys. Not the tokens themselves — the values under them
         * are, and that is the whole reason this class exists.
         */
        const val KEY_ACCESS = "auth.access_token"
        const val KEY_REFRESH = "auth.refresh_token"
        const val KEY_EMAIL = "auth.user_email"
    }
}
