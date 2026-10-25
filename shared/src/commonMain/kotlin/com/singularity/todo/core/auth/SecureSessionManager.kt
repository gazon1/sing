package com.singularity.todo.core.auth

import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The session as the credential store holds it: one value, one write.
 *
 * ## Why the record is the project's own and not the SDK's
 *
 * The SDK's [UserSession] carries expiry, provider tokens, confirmation timestamps and a
 * source enum. None of that is read by this application, and all of it would have to be
 * kept parseable forever: a payload written by one version of the SDK and read by another
 * is a payload that can stop being read at all. The record below holds the five things
 * the app actually uses and nothing else, and the seam converts it to and from the SDK's
 * type in one place.
 *
 * ## Why the owner id is nullable
 *
 * Because a session without an owner is a state this application has devices in. The
 * old plain-text migration wrote an access token, a refresh token and an address, and
 * no owner id — the plain preferences never held one. Such a record is kept rather than
 * dropped: `REQ-UA-013` says a plain-text token is *moved into the secure store and
 * erased*, and dropping it would leave the token in plain text forever while telling
 * the user they were signed out. The restore finds no owner in it and ends signed out,
 * which is exactly what those devices do today.
 */
@Serializable
internal data class StoredSession(
    /** The bearer token for the API. */
    val accessToken: String,
    /** The token that buys a new access token. */
    val refreshToken: String,
    /** The account this session belongs to, or null for a record that has no owner. */
    val ownerId: String? = null,
    /** The account's address, when the record has one. */
    val email: String? = null,
    /** When the access token stops being valid, in epoch milliseconds. Zero when unknown. */
    val expiresAtMillis: Long = 0L,
) {

    /**
     * The SDK's session, for the plugin to work with.
     *
     * The audience is empty because the SDK's [UserInfo] requires one and this
     * application never reads it: the owner id is what every synchronised row is
     * scoped to, and it comes from the record, not from the token.
     */
    fun toUserSession(now: Instant = Clock.System.now()): UserSession {
        val expiresAt = if (expiresAtMillis > 0) Instant.fromEpochMilliseconds(expiresAtMillis) else now
        return UserSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = if (expiresAtMillis > 0) (expiresAt - now).inWholeSeconds else 0L,
            tokenType = TOKEN_TYPE,
            user = ownerId?.let { UserInfo(aud = "", id = it, email = email) },
            expiresAt = expiresAt,
        )
    }

    /**
     * Whether [other] carries the same credentials as this record.
     *
     * The address is left out on purpose: a record without an owner cannot carry its
     * address through the SDK's session type, so a round trip through the plugin would
     * drop it — and a write that only "changes" that address is not a change worth
     * making, because the address of a session with no owner is not the app's to keep.
     */
    fun hasSameCredentialsAs(other: StoredSession): Boolean =
        accessToken == other.accessToken &&
            refreshToken == other.refreshToken &&
            ownerId == other.ownerId

    companion object {
        /** The token type Supabase issues. */
        const val TOKEN_TYPE = "bearer"

        /** The SDK's session, as this project stores it. */
        fun of(session: UserSession): StoredSession = StoredSession(
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            ownerId = session.user?.id,
            email = session.user?.email,
            expiresAtMillis = session.expiresAt.toEpochMilliseconds(),
        )
    }
}

/**
 * The credential store, as the SDK's own [SessionManager].
 *
 * ## Why the SDK owns the session
 *
 * The plugin has to keep a session to send authenticated requests at all, and it keeps
 * it wherever the [SessionManager] says. Left alone, that is its own settings store —
 * a plain-text file, which is the very thing [DataStoreSessionStore] was moved out of.
 * Installing this class instead puts the one session both sides read in the keychain,
 * so there is exactly one copy of a bearer credential on the device and the SDK cannot
 * refresh a token the application does not know about.
 *
 * ## Why one value and not four
 *
 * The previous layout wrote the access token, the refresh token, the address and the
 * owner id as four separate keychain entries, and undid a partial write when one of
 * the four failed — because a half-written identity is worse than none: the device
 * reads back two fields of the new session beside two fields of the old one, and
 * concludes it is signed in as an owner the fields disagree about. One value cannot be
 * half-written. It is also what `REQ-UA-012` asks for, more strongly than write-then-undo.
 *
 * ## Why the migration writes before it erases
 *
 * The order is **write to the new key first, erase the old source second**, and it is
 * the whole design. The reverse order has a window in which a crash, a battery pull or
 * a force-stop leaves the user signed out of their own account with no way back,
 * because the token was in the place being emptied and already gone from the one being
 * filled. This way a crash at any point leaves the token in at least one place, and the
 * next launch retries.
 *
 * A failed write therefore does **not** erase. The old copy stays, and the next attempt
 * tries again — which is the correct outcome, because a plain-text token is a problem
 * but a missing token is a user who has lost their account.
 *
 * ## Why there are two legacy sources, and the keychain wins
 *
 * The previous layout is four keychain values. The layout before that is plain-text
 * preferences. The keychain is the newer of the two, so it is read first, and plain
 * text is consulted only when the keychain has nothing. A device that holds both has
 * been through the keychain migration already, and the newer session is the live one.
 *
 * ## Why the migration is not done in the constructor
 *
 * Storage is suspending and can fail, and this class is built in the Koin graph before
 * any screen exists. Doing it lazily, on the first read of the session, means the work
 * happens where a failure has somewhere to go, and a user who never reaches a screen
 * that needs the session never pays for it.
 *
 * ## Why the store serialises its writes
 *
 * Because a write and a delete that overlap can undo each other, and only one of the two
 * outcomes is acceptable. The SDK writes the session after every sign-in and every
 * refresh, and deletes it on every sign-out, so both calls arrive from the plugin without
 * the application knowing they are about to collide.
 *
 * The lock below is taken by every operation that touches the store's contents — the
 * write, the delete, the migration and the read — and it is *fair*: a waiter that has
 * been waiting longest is served first. So a write that was asked for before a delete is
 * served first, and the delete runs after it and erases what it wrote. The store ends
 * empty, which is what a sign-out asked for. A record that reappears after a sign-out is
 * a session the user cannot end.
 *
 * The reverse order is not a race: a write asked for *after* the delete has run is a
 * sign-in, and it is allowed to write.
 *
 * The cost is that a sign-out waits for a write that is already in flight. That is the
 * cheaper of the two costs: a sign-out that waits for a keychain write is a sign-out
 * that takes a moment, and a sign-out that does not wait is a sign-out that can be
 * undone.
 */
class SecureSessionManager(
    private val log: Logger,
    private val secure: SecureStorage,
    /**
     * Where a pre-upgrade token may still sit. Read-only in practice: this class only
     * ever *removes* from it, and only after the value is safely stored.
     */
    private val legacy: DataStoreSessionStore,
) : SessionManager {

    /**
     * The lock every operation on the store's contents takes.
     *
     * One lock, not one per operation: a write that did not see a delete would defeat
     * the point of taking it. See the class's own note on why the store serialises its
     * writes.
     */
    private val state = Mutex()

    /**
     * Whether the legacy sources have been dealt with in this process.
     *
     * Only ever read and written under [state], so it needs no memory of its own.
     */
    private var migrated = false

    /**
     * Writes the session the plugin is holding.
     *
     * The plugin calls this after every sign-in and every refresh, and it calls it
     * again when it loads a session it has just read — so a write that changes
     * nothing is not written at all. Without that, every cold start would rewrite
     * the keychain, and a keychain that fails that write would take the session
     * down with it.
     */
    override suspend fun saveSession(session: UserSession) {
        store(StoredSession.of(session))
    }

    /**
     * Writes the session the application knows about.
     *
     * The SDK's own [saveSession] takes the SDK's session type, and a caller outside
     * the vendor seam must not have to name a vendor type to write a session. Both
     * write the same record, through the same path.
     */
    suspend fun save(session: Session.SignedIn) {
        store(
            StoredSession(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                ownerId = session.userId.value,
                email = session.email,
            ),
        )
    }

    /**
     * The stored session, in the application's own shape.
     *
     * Null when there is none, and null when the record has no owner — a record
     * without an owner is not a session, and the same reason applies here as in the
     * seam: there is nobody for it to be.
     */
    suspend fun currentSession(): Session.SignedIn? {
        val record = readStored() ?: return null
        val owner = record.ownerId ?: return null
        return Session.SignedIn(
            userId = UserId.fromString(owner),
            email = record.email.orEmpty(),
            accessToken = record.accessToken,
            refreshToken = record.refreshToken,
        )
    }

    /**
     * Writes the record, unless the store already holds the same credentials.
     *
     * The plugin calls this after every sign-in and every refresh, and it calls it
     * again when it loads a session it has just read — so a write that changes
     * nothing is not written at all. Without that, every cold start would rewrite
     * the keychain, and a keychain that fails that write would take the session
     * down with it.
     */
    private suspend fun store(record: StoredSession) {
        state.withLock {
            // Suppress the migration for this write: the caller has just handed us a
            // current token, and moving a stale one first would overwrite it.
            migrated = true
            val stored = readStoredLocked()
            if (stored != null && stored.hasSameCredentialsAs(record)) {
                return@withLock
            }
            write(record)
        }
    }

    /**
     * The stored session.
     *
     * @throws NoSessionFoundException when the device holds none. That is the ordinary
     * signed-out state, not a failure, and the SDK treats it as one.
     */
    override suspend fun loadSession(): UserSession {
        val record = readStored() ?: throw NoSessionFoundException()
        return record.toUserSession()
    }

    /**
     * Ends the session on this device.
     *
     * Every copy goes, not only the current one: a legacy source that survived would
     * be moved into the new key on the next launch, and the user who asked to sign out
     * would be signed in again by the migration that was meant to help them.
     */
    override suspend fun deleteSession() {
        state.withLock {
            secure.delete(KEY_SESSION)
            eraseLegacyKeychainLayout()
            legacy.forgetTokens()
        }
    }

    /**
     * The stored record, or null when there is none.
     *
     * A record that cannot be decoded is treated as no session. The alternative — a
     * half-readable credential — is a session the app cannot use and cannot safely
     * keep, and the user is better served by the login screen than by an account that
     * fails on every request.
     */
    private suspend fun readStored(): StoredSession? = state.withLock { readStoredLocked() }

    /**
     * The stored record, or null when there is none.
     *
     * The caller holds [state], so the read cannot observe a delete that is halfway
     * through: it either sees the record or sees nothing.
     */
    private suspend fun readStoredLocked(): StoredSession? {
        migrateOnceLocked()
        val raw = secure.read(KEY_SESSION) ?: return null
        return runCatchingCancellable { StableJson.decodeFromString<StoredSession>(raw) }
            .onFailure { cause ->
                log.w(cause) { "The stored session could not be read; the device is treated as signed out" }
            }
            .getOrNull()
    }

    /**
     * Writes the record, and reports a failure that names the local cause.
     *
     * A failed write leaves the store without a session. The previous layout had to
     * undo its partial write for the same reason, and the reason is the same: a
     * session that is half there is a session the device cannot use.
     */
    private suspend fun write(record: StoredSession) {
        runCatchingCancellable { secure.write(KEY_SESSION, StableJson.encodeToString(record)) }
            .onFailure { cause ->
                runCatchingCancellable { secure.delete(KEY_SESSION) }
                    .onFailure { log.w(it) { "Could not clear the session after a failed write" } }
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
    }

    /**
     * Moves a session out of a legacy source, once.
     *
     * The flag is set when the attempt ends, not when it starts: a source that cannot
     * be read is worth one attempt per launch, and an attempt that failed must not be
     * retried in a loop against a keychain that is already failing.
     *
     * A cancellation is the one thing that leaves the flag alone. A cancellation is not
     * a keychain that cannot be read — it is a launch that ended early — and the next
     * launch has to finish the job: the token is already in the new place, the old copy
     * is still there, and only the erase is missing.
     *
     * The caller holds [state].
     */
    private suspend fun migrateOnceLocked() {
        if (migrated) return
        val attempt = runCatchingCancellable {
            // A session already in the current layout is the newest one there is.
            // The older copies are erased rather than imported: importing them would
            // move this device *backwards*, onto a refresh token that was rotated on
            // the way out and is usually already revoked — a sign-in that works once
            // and then fails, for a user who was signed in the whole time.
            if (secure.read(KEY_SESSION) != null) {
                eraseLegacyKeychainLayout()
                legacy.forgetTokens()
            } else {
                moveTheOldSession()
            }
        }
        migrated = true
        attempt.exceptionOrNull()?.let { cause ->
            log.w(cause) { "The legacy session could not be moved; the old copy is kept" }
        }
    }

    /**
     * Moves the session out of the old layout, if there is one.
     *
     * The keychain layout comes first: it is the newer of the two, and a device
     * that has both has been written to by both — the keychain one is the one the
     * last release wrote.
     */
    private suspend fun moveTheOldSession() {
        val fromKeychain = readLegacyKeychainLayout()
        if (fromKeychain != null) {
            move(fromKeychain) { eraseLegacyKeychainLayout() }
            return
        }
        val fromPlainText = readLegacyPlainTextLayout()
        if (fromPlainText != null) {
            move(fromPlainText) { legacy.forgetTokens() }
        }
    }

    /**
     * Writes the record and erases the source, in that order.
     *
     * A failed write erases nothing: the copy stays where it was, and the next launch
     * tries again.
     */
    private suspend fun move(record: StoredSession, erase: suspend () -> Unit) {
        runCatchingCancellable { secure.write(KEY_SESSION, StableJson.encodeToString(record)) }
            .onSuccess {
                runCatchingCancellable { erase() }
                    .onFailure { cause -> log.w(cause) { "Could not erase the migrated session" } }
            }
            .onFailure { cause ->
                log.w(cause) { "Could not move the session into the secure store; the old copy is kept" }
            }
    }

    /** The previous layout: four keychain values. */
    private suspend fun readLegacyKeychainLayout(): StoredSession? {
        val access = secure.read(LEGACY_KEY_ACCESS) ?: return null
        return StoredSession(
            accessToken = access,
            refreshToken = secure.read(LEGACY_KEY_REFRESH).orEmpty(),
            ownerId = secure.read(LEGACY_KEY_USER_ID),
            email = secure.read(LEGACY_KEY_EMAIL),
        )
    }

    /**
     * The layout before that: plain-text preferences.
     *
     * No owner id, because the plain preferences never held one — which is what makes
     * this a record without an owner rather than a session.
     */
    private suspend fun readLegacyPlainTextLayout(): StoredSession? {
        val access = legacy.accessToken.first() ?: return null
        return StoredSession(
            accessToken = access,
            refreshToken = legacy.refreshToken.first().orEmpty(),
            ownerId = null,
            email = legacy.userEmail.first(),
        )
    }

    /** Erases the previous layout's four values. Removing is idempotent. */
    private suspend fun eraseLegacyKeychainLayout() {
        secure.delete(LEGACY_KEY_ACCESS)
        secure.delete(LEGACY_KEY_REFRESH)
        secure.delete(LEGACY_KEY_EMAIL)
        secure.delete(LEGACY_KEY_USER_ID)
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
         * The key the session is stored under.
         *
         * Not the token itself — the value under it is, and that is the whole reason
         * this class exists.
         */
        const val KEY_SESSION = "auth.session"

        /** The previous layout's keys, read once and erased. */
        const val LEGACY_KEY_ACCESS = "auth.access_token"
        const val LEGACY_KEY_REFRESH = "auth.refresh_token"
        const val LEGACY_KEY_EMAIL = "auth.user_email"

        /**
         * The owner id, stored beside the tokens in the previous layout.
         *
         * Not derivable from them: a JWT's subject is, but decoding it here to
         * recover an id the client already knows would mean trusting a token to
         * tell the client who it is, on a path where the token may be expired and
         * unverifiable. The alternative — publishing a session whose owner is
         * unknown, or inventing a placeholder — puts a wrong id in front of the
         * sync scope provider, and every row it then writes is attributed to an
         * owner that does not exist.
         */
        const val LEGACY_KEY_USER_ID = "auth.user_id"
    }
}
