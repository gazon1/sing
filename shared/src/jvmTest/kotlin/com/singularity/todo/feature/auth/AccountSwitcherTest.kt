package com.singularity.todo.feature.auth

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.core.sync.PushSummary
import com.singularity.todo.core.sync.PullSummary
import com.singularity.todo.core.sync.SyncOutcome
import com.singularity.todo.test.fakes.FakeAppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Replacing an account: the departing one is delivered, then erased, and nothing is
 * erased unless the delivery happened.
 *
 * Notes are used as the fixture rather than profiles, because the erase deliberately
 * stops short of the profile rows themselves — a profile is ownable but is not yet
 * decided to be erased with the account. Asserting on a table the erase does not touch
 * would have passed whether or not anything ran.
 *
 * Each failure case asserts **both** that the data is still there and that nobody was
 * signed in. A switch that erased and then failed looks identical to one that worked,
 * from the data alone, and it is the one that costs the user everything.
 *
 * @see AccountSwitcher for why the order is delivery, then erase.
 */
@Tag("fast")
class AccountSwitcherTest {

    private val outgoing = UserId("u1")
    private val email = "new@example.com"
    private val password = "correct-horse-battery"

    private fun note(id: String, userId: String) = NoteEntity(
        id = id,
        userId = userId,
        title = id,
        bodyMarkdown = null,
        bodyHtml = null,
        parentNoteId = null,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        deletedAt = null,
        archivedAt = null,
    )

    /** One note for the departing account and one for whoever is already signed in. */
    private fun db() = FakeAppDatabase().apply {
        seedNotes(listOf(note("mine", outgoing.value), note("theirs", "u9")))
    }

    private fun auth(
        signedInAs: UserId? = outgoing,
        signInResult: Result<Unit> = Result.success(Unit),
    ) = RecordingAuthRepository(signedInAs, signInResult)

    private fun switcher(
        auth: AuthRepository,
        sync: FakeSyncRepository,
        db: FakeAppDatabase,
    ) = AccountSwitcher(
        authRepository = auth,
        syncRepository = sync,
        resolver = OwnerRowIdResolver(db),
        eraser = OwnerScopedEraser(db),
    )

    private fun deliveredCycle(push: Result<PushSummary> = Result.success(PushSummary(2, 2, 0))) =
        SyncOutcome.Success(push, Result.success(PullSummary(0, 0, 0)))

    @Test
    fun `the departing account's data is erased and the new one is signed in`() = runTest {
        val db = db()
        val auth = auth()
        val sync = FakeSyncRepository().apply { syncOnceResult = Result.success(deliveredCycle()) }

        val result = switcher(auth, sync, db).switchTo(email, password)

        assertTrue(result.isSuccess, "the switch failed: ${result.exceptionOrNull()}")
        assertEquals(1, auth.signInCalls, "the incoming account was not signed in")
        assertTrue(db.noteDao().watchAll(outgoing.value).first().isEmpty(), "the departing account's note survived")
    }

    @Test
    fun `another account's data is not erased by the switch`() = runTest {
        val db = db()
        val sync = FakeSyncRepository().apply { syncOnceResult = Result.success(deliveredCycle()) }

        switcher(auth(), sync, db).switchTo(email, password)

        assertEquals(
            listOf("theirs"),
            db.noteDao().watchAll("u9").first().map { it.id },
            "the switch erased a row belonging to another account",
        )
    }

    @Test
    fun `a delivery that fails erases nothing and signs in nobody`() = runTest {
        val db = db()
        val auth = auth()
        val sync = FakeSyncRepository().apply {
            syncOnceResult = Result.success(deliveredCycle(Result.failure(AppError.Network("offline"))))
        }

        val result = switcher(auth, sync, db).switchTo(email, password)

        assertTrue(result.isFailure, "an undelivered switch reported success")
        assertEquals(0, auth.signInCalls, "the incoming account was signed in despite a failed delivery")
        assertEquals(1, db.noteDao().watchAll(outgoing.value).first().size, "the note was erased anyway")
    }

    @Test
    fun `a cycle that never ran erases nothing`() = runTest {
        val db = db()
        val auth = auth()
        // `Skipped` means the coordinator had no work to give, so nothing was sent —
        // which is not the same as "everything arrived".
        val sync = FakeSyncRepository().apply {
            syncOnceResult = Result.success(SyncOutcome.Skipped("the coordinator is closed"))
        }

        val result = switcher(auth, sync, db).switchTo(email, password)

        assertTrue(result.isFailure, "a cycle that never ran was treated as delivered")
        assertEquals(1, db.noteDao().watchAll(outgoing.value).first().size)
    }

    @Test
    fun `a push that failed erases nothing`() = runTest {
        val db = db()
        val auth = auth()
        // One of three patches refused. The other two being fine is exactly why a count
        // rather than a boolean decides this.
        val sync = FakeSyncRepository().apply {
            syncOnceResult = Result.success(deliveredCycle(Result.success(PushSummary(3, 2, 1))))
        }

        val result = switcher(auth, sync, db).switchTo(email, password)

        assertTrue(result.isFailure, "a partially-failed delivery reported success")
        assertEquals(1, db.noteDao().watchAll(outgoing.value).first().size)
    }

    @Test
    fun `a discarded push response erases nothing`() = runTest {
        val db = db()
        val auth = auth()
        // Discarded rather than failed: the server answered under an account that is no
        // longer this one, so this account never saw the work acknowledged. Erasing it
        // now would lose an edit that exists nowhere else.
        val sync = FakeSyncRepository().apply {
            syncOnceResult = Result.success(
                deliveredCycle(Result.success(PushSummary(processed = 1, succeeded = 0, failed = 0, discarded = 1))),
            )
        }

        val result = switcher(auth, sync, db).switchTo(email, password)

        assertTrue(result.isFailure, "a discarded push response was treated as delivered")
        assertEquals(1, db.noteDao().watchAll(outgoing.value).first().size)
    }

    @Test
    fun `a rejected sign-in leaves the device signed in to the old account`() = runTest {
        val db = db()
        val auth = auth(signInResult = Result.failure(AppError.Unauthorized("bad password")))
        val sync = FakeSyncRepository().apply { syncOnceResult = Result.success(deliveredCycle()) }

        val result = switcher(auth, sync, db).switchTo(email, password)

        assertTrue(result.isFailure)
        assertEquals(
            outgoing,
            auth.currentSession.value.ownerIdOrNull(),
            "a rejected sign-in left the device signed in to nobody",
        )
    }

    @Test
    fun `a refused switch names its reason rather than leaving a spinner`() = runTest {
        val db = db()
        val sync = FakeSyncRepository().apply {
            syncOnceResult = Result.success(deliveredCycle(Result.failure(AppError.Network("offline"))))
        }
        val subject = switcher(auth(), sync, db)

        subject.switchTo(email, password)

        assertIs<SwitchStep.Refused>(subject.progress.value)
    }

    @Test
    fun `switching while signed out is refused rather than erasing anything`() = runTest {
        val db = db()
        val auth = auth(signedInAs = null)
        val sync = FakeSyncRepository()

        val result = switcher(auth, sync, db).switchTo(email, password)

        assertTrue(result.isFailure)
        assertFalse(sync.syncOnceCalled, "a switch with no departing account tried to deliver something")
        assertEquals(1, db.noteDao().watchAll(outgoing.value).first().size)
    }

    @Test
    fun `an invalid password is caught before anything irreversible`() = runTest {
        val db = db()
        val sync = FakeSyncRepository()

        val result = switcher(auth(), sync, db).switchTo("new@example.com", "x")

        assertTrue(result.isFailure)
        assertFalse(sync.syncOnceCalled, "an obviously invalid password still reached the network")
        assertEquals(1, db.noteDao().watchAll(outgoing.value).first().size)
    }

    /** Records what it was asked, and signs in as configured. */
    private class RecordingAuthRepository(signedInAs: UserId?, private val signInResult: Result<Unit>) :
        AuthRepository {
        private val session = MutableStateFlow<Session>(
            signedInAs?.let { Session.SignedIn(it, "old@example.com", "access", "refresh") } ?: Session.SignedOut,
        )
        override val currentSession: StateFlow<Session> = session.asStateFlow()
        override val isLoading = MutableStateFlow(false)

        var signInCalls = 0

        override suspend fun signUp(email: String, password: String): Result<Unit> = signInResult

        override suspend fun signIn(email: String, password: String): Result<Unit> {
            signInCalls++
            // Explicit rather than `getOrElse { return … }`: the non-local return inside
            // an inline lambda infers its result type from the constraint `T : R`, which
            // is not worth depending on in the one fake that decides whether a refused
            // sign-in was honoured.
            if (signInResult.isFailure) return signInResult
            session.value = Session.SignedIn(UserId("u2"), email, "a", "r")
            return Result.success(Unit)
        }

        override suspend fun signInAnonymously(): Result<Unit> = Result.success(Unit)
        override suspend fun signOut(): Result<Unit> = Result.success(Unit)
        override suspend fun migrateAnonymousTo(email: String, password: String): Result<Unit> =
            Result.success(Unit)
    }
}

private fun Session.ownerIdOrNull(): UserId? = when (this) {
    is Session.SignedIn -> userId
    is Session.Anonymous -> userId
    Session.Loading, Session.SignedOut -> null
}
