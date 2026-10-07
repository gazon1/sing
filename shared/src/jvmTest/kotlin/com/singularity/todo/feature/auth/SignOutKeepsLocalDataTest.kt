package com.singularity.todo.feature.auth

import com.singularity.todo.core.auth.AuthGateway
import com.singularity.todo.core.auth.DataStoreSessionStore
import com.singularity.todo.core.auth.FixedIdGenerator
import com.singularity.todo.core.auth.MapSecureStorage
import com.singularity.todo.core.auth.RemoteSession
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.auth.SecureSessionStore
import com.singularity.todo.core.auth.SupabaseAuthRepository
import com.singularity.todo.core.auth.newPreferencesStore
import com.singularity.todo.core.auth.testLogger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.test.fakes.FakeAppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Signing out keeps local data. Switching accounts erases it.
 *
 * The two are the same gesture to the user and opposite operations, which is why this
 * lives beside `AccountSwitcherTest` rather than in the auth repository's own suite: the
 * distinction only means anything when both sides are visible, and a test of `signOut`
 * alone passes just as happily against an implementation that erases.
 *
 * `signOut` also has to work with no network. The user asking to be signed out is not
 * asking a question about connectivity, and a plane is the ordinary case rather than the
 * exception.
 */
@Tag("fast")
class SignOutKeepsLocalDataTest {

    private val owner = UserId("u1")

    private fun note(id: String) = NoteEntity(
        id = id,
        userId = owner.value,
        title = id,
        bodyMarkdown = null,
        bodyHtml = null,
        parentNoteId = null,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        deletedAt = null,
        archivedAt = null,
    )

    private fun TestScope.repository(gateway: AuthGateway) = SupabaseAuthRepository(
        log = testLogger(),
        gateway = gateway,
        sessionStore = SecureSessionStore(
            log = testLogger(),
            secure = MapSecureStorage(),
            legacy = DataStoreSessionStore(newPreferencesStore(), FixedIdGenerator("dev")),
        ),
        scope = AutoCloseableCoroutineScope(coroutineContext),
    )

    @Test
    fun `signing out with the server unreachable keeps local rows`() = runTest {
        val db = FakeAppDatabase()
        db.seedNotes(listOf(note("keep-me")))
        val repo = repository(UnreachableGateway())

        val result = repo.signOut()

        assertTrue(result.isSuccess, "signing out failed against an unreachable server")
        assertEquals(
            listOf("keep-me"),
            db.noteDao().watchAll(owner.value).first().map { it.id },
            "signing out erased local data — that is the switch path, not this one",
        )
    }

    @Test
    fun `signing out clears the session`() = runTest {
        val gateway = UnreachableGateway()
        val repo = repository(gateway)

        repo.signOut()

        assertNull(
            (repo.currentSession.value as? Session.SignedIn)?.userId,
            "signing out left the session signed in",
        )
    }

    /** Fails every call, the way a device with no connectivity would. */
    private class UnreachableGateway : AuthGateway {
        override suspend fun signUp(email: String, password: String): Result<RemoteSession> =
            Result.failure(AppError.Network("offline"))

        override suspend fun signIn(email: String, password: String): Result<RemoteSession> =
            Result.failure(AppError.Network("offline"))

        override suspend fun signInAnonymously(): Result<RemoteSession> =
            Result.failure(AppError.Network("offline"))

        override suspend fun attachEmail(email: String, password: String): Result<RemoteSession> =
            Result.failure(AppError.Network("offline"))

        override suspend fun refresh(accessToken: String, refreshToken: String): Result<RemoteSession?> =
            Result.failure(AppError.Network("offline"))

        override suspend fun signOut(): Result<Unit> = Result.failure(AppError.Network("offline"))
    }
}
