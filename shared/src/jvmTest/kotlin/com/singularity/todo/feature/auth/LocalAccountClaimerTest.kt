@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.auth

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.database.ProfileEntity
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.test.fakes.FakeAppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The offline → account path, driven through a repository that can be told to refuse.
 *
 * ## Why these tests are about ordering
 *
 * Every one of them is a case where the *sequence* matters rather than any single step.
 * A wrong password before anything moves, a failure after the account is signed in, a
 * choice that must not be applied silently — none of these is visible from the end state
 * alone, and an implementation that reordered the steps would pass a test that only
 * checked the outcome.
 */
@Tag("fast")
class LocalAccountClaimerTest {

    private val local = UserId.fromString("local:dev-0")

    @Test
    fun `keeping the data moves the rows and leaves the account signed in`() = runTest {
        val db = FakeAppDatabase()
        // The profile has to exist: a scope is resolved from the profiles the owner
        // actually owns, so a row filed under a profile nobody owns is invisible to the
        // transfer — the same rule `OwnerScopedEraser` follows, and the reason a test
        // that seeds only the row would be asserting something the design never promised.
        db.seedProfiles(listOf(profile("prof-b", local.value)))
        db.seedTasks(listOf(task("t1", local.value), task("t2", "prof-b/${local.value}")))
        val repository = ScriptedAuth(local)
        val claimer = claimer(repository, db)

        val steps = claimer.claim(
            "alice@example.com",
            "password123",
            LocalDataDisposition.Keep(ReassignCounts(), LocalDataSummary(0, 0)),
        ).toList()

        assertTrue(steps.last() is ClaimStep.Done, "the terminal step must be Done")
        assertEquals("acct-1", db.taskOwner("t1"), "the bare scope becomes the bare account")
        assertEquals("prof-b/acct-1", db.taskOwner("t2"), "a profile keeps its profile")
        assertEquals(UserId.fromString("acct-1"), assertIs<Session.SignedIn>(repository.currentSession.value).userId)
        assertEquals(1, repository.signInCalls, "exactly one sign-in, before the rows moved")
        assertTrue(steps.contains(ClaimStep.SigningIn), "SigningIn step must be emitted")
    }

    @Test
    fun `keeping the data claims ownerless profiles for the account`() = runTest {
        val db = FakeAppDatabase()
        db.seedProfiles(
            listOf(
                ProfileEntity(
                    id = "prof-u",
                    name = "Unowned",
                    emoji = "",
                    colorIdx = 0,
                    isDefault = false,
                    createdAt = 0L,
                    updatedAt = 0L,
                    userId = null,
                ),
            ),
        )
        val repository = ScriptedAuth(local)
        val claimer = claimer(repository, db)

        claimer.claim(
            "alice@example.com",
            "password123",
            LocalDataDisposition.Keep(ReassignCounts(), LocalDataSummary(0, 0)),
        ).toList()

        assertEquals("acct-1", db.profileDao().getById("prof-u")?.userId, "ownerless profile is claimed by the account")
    }

    @Test
    fun `discarding the data erases it and leaves the account empty`() = runTest {
        val db = FakeAppDatabase()
        db.seedTasks(listOf(task("t1", local.value)))
        val repository = ScriptedAuth(local)

        val steps = claimer(
            repository,
            db,
        ).claim("alice@example.com", "password123", LocalDataDisposition.Discard(EraseCounts())).toList()

        assertTrue(steps.last() is ClaimStep.Done)
        assertEquals(0, db.tasksOwnedBy(local.value), "the local rows are gone")
        assertEquals(0, db.tasksOwnedBy("acct-1"), "and they were not copied either")
        assertIs<Session.SignedIn>(repository.currentSession.value)
    }

    @Test
    fun `a wrong password changes nothing at all`() = runTest {
        val db = FakeAppDatabase()
        db.seedTasks(listOf(task("t1", local.value)))
        val repository = ScriptedAuth(local, signInResult = Result.failure(AppError.Unauthorized("nope")))
        val claimer = claimer(repository, db)

        val steps = claimer.claim(
            "alice@example.com",
            "wrong-password",
            LocalDataDisposition.Keep(ReassignCounts(), LocalDataSummary(0, 0)),
        ).toList()

        assertIs<ClaimStep.Refused>(steps.last(), "Refused must be the terminal step")
        assertEquals(local.value, db.taskOwner("t1"), "the data is exactly where it was")
        assertIs<Session.Anonymous>(repository.currentSession.value)
        assertEquals(0, repository.signOutCalls, "nothing to undo, so nothing was torn down")
        assertTrue(steps.contains(ClaimStep.SigningIn), "SigningIn step must be emitted even on refusal")
    }

    @Test
    fun `a failed transfer puts the device back on its local session`() = runTest {
        val db = FakeAppDatabase()
        db.seedTasks(listOf(task("t1", local.value)))
        val repository = ScriptedAuth(local)
        val claimer = claimer(repository, db)
        db.failReassign = true

        val steps = claimer.claim(
            "alice@example.com",
            "password123",
            LocalDataDisposition.Keep(ReassignCounts(), LocalDataSummary(0, 0)),
        ).toList()

        assertIs<ClaimStep.Refused>(steps.last())
        assertIs<Session.Anonymous>(
            repository.currentSession.value,
            "the user keeps the offline session and their data rather than being left in an " +
                "account whose data is missing rows",
        )
        assertEquals(1, repository.signOutCalls, "the account they could not get into is closed")
        assertEquals(1, repository.startLocalSessionCalls)
    }

    @Test
    fun `restoring local session after failed signOut returns signOut failure as the terminal step`() = runTest {
        val db = FakeAppDatabase()
        db.seedTasks(listOf(task("t1", local.value)))
        val signOutError = AppError.Network("sign-out failed")
        val repository = ScriptedAuth(local, signOutResult = Result.failure(signOutError))
        val claimer = claimer(repository, db)
        db.failReassign = true // forces restoreLocal path

        val steps = claimer.claim(
            "alice@example.com",
            "password123",
            LocalDataDisposition.Keep(ReassignCounts(), LocalDataSummary(0, 0)),
        ).toList()

        val refused = assertIs<ClaimStep.Refused>(steps.last())
        assertEquals(
            signOutError,
            refused.reason,
            "signOut failure is the terminal Refused reason, not the transfer error",
        )
        assertIs<Session.Anonymous>(repository.currentSession.value, "local session is still restored")
        assertEquals(1, repository.signOutCalls, "signOut was called to clean up the half-signed-in account")
        assertEquals(1, repository.startLocalSessionCalls)
        assertIs<ClaimStep.SignOutFailed>(
            steps.dropLast(1).lastOrNull(),
            "SignOutFailed is emitted as the step before the terminal Refused",
        )
    }

    @Test
    fun `an invalid address is refused before the provider is asked`() = runTest {
        val db = FakeAppDatabase()
        val repository = ScriptedAuth(local)
        val claimer = claimer(repository, db)

        val steps = claimer.claim(
            "not-an-address",
            "password123",
            LocalDataDisposition.Keep(ReassignCounts(), LocalDataSummary(0, 0)),
        ).toList()

        assertIs<ClaimStep.Refused>(steps.last())
        assertEquals(0, repository.signInCalls)
    }

    @Test
    fun `a signed-in session is refused before anything is touched`() = runTest {
        val db = FakeAppDatabase()
        val account = UserId.fromString("acct-9")
        val repository = ScriptedAuth(
            owner = account,
            session = Session.SignedIn(account, "a@b.c", "access", "refresh"),
        )
        val claimer = claimer(repository, db)

        val steps = claimer.claim(
            "alice@example.com",
            "password123",
            LocalDataDisposition.Keep(ReassignCounts(), LocalDataSummary(0, 0)),
        ).toList()

        val refused = assertIs<ClaimStep.Refused>(steps.last())
        assertEquals("auth.not_local", refused.reason.code)
        assertEquals(0, repository.signInCalls)
    }

    @Test
    fun `the summary counts what the user stands to lose`() = runTest {
        val db = FakeAppDatabase()
        db.seedTasks(listOf(task("t1", local.value), task("t2", local.value)))

        val disposition = claimer(ScriptedAuth(local), db).summarise(local)
        val keep = disposition as LocalDataDisposition.Keep

        assertEquals(2, keep.transferCounts.tasks)
        assertEquals(0, keep.transferCounts.notes)
        assertEquals("2 tasks", keep.summary.describe)
    }

    @Test
    fun `the summary describes one row in the singular`() = runTest {
        assertEquals("1 task", LocalDataSummary(tasks = 1, notes = 0).describe)
        assertEquals("Nothing yet", LocalDataSummary(tasks = 0, notes = 0).describe)
    }

    // ── wiring ────────────────────────────────────────────────────────────────

    private fun claimer(repository: ScriptedAuth, db: FakeAppDatabase) = LocalAccountClaimer(
        authRepository = repository,
        resolver = OwnerRowIdResolver(db),
        reassigner = OwnerScopedReassigner(db),
        eraser = OwnerScopedEraser(db),
        calculator = OwnerCountsCalculator(db),
    )

    private fun profile(id: String, owner: String) = ProfileEntity(
        id = id,
        name = "Work",
        emoji = "",
        colorIdx = 0,
        isDefault = false,
        createdAt = 0L,
        updatedAt = 0L,
        userId = owner,
    )

    private fun task(id: String, owner: String) = TaskEntity(
        id = id,
        title = "task $id",
        description = null,
        projectId = null,
        dueDate = null,
        dueTime = null,
        startDate = null,
        startTime = null,
        endDate = null,
        endTime = null,
        archivedAt = null,
        accentColor = null,
        emoji = null,
        completedAt = null,
        createdAt = 0L,
        updatedAt = 0L,
        userId = owner,
    )
}

/**
 * A repository that records what it was asked and can be told to refuse a sign-in.
 *
 * The counts exist because the ordering claims in the tests above are claims about
 * *when* things happened, and "the data was not moved" is not enough to tell "the
 * transfer never ran" from "the transfer ran and rolled back".
 */
private class ScriptedAuth(
    private val owner: UserId,
    private val session: Session = Session.Anonymous(owner),
    private val signInResult: Result<Unit> = Result.success(Unit),
    private val signOutResult: Result<Unit> = Result.success(Unit),
) : AuthRepository {
    private val _session = MutableStateFlow(session)
    override val currentSession = _session.asStateFlow()
    override val isLoading = MutableStateFlow(false).asStateFlow()

    var signInCalls = 0
        private set
    var signOutCalls = 0
        private set
    var startLocalSessionCalls = 0
        private set

    override suspend fun signIn(email: String, password: String): Result<Unit> {
        signInCalls++
        if (signInResult.isFailure) return signInResult
        _session.value = Session.SignedIn(
            UserId.fromString("acct-1"),
            email,
            accessToken = "access",
            refreshToken = "refresh",
        )
        return Result.success(Unit)
    }

    override suspend fun signOut(): Result<Unit> {
        signOutCalls++
        if (signOutResult.isFailure) return signOutResult
        _session.value = Session.SignedOut
        return Result.success(Unit)
    }

    override suspend fun startLocalSession(): Result<Unit> {
        startLocalSessionCalls++
        _session.value = Session.Anonymous(owner)
        return Result.success(Unit)
    }

    override suspend fun signUp(email: String, password: String) = Result.success(Unit)
    override suspend fun updatePassword(newPassword: String) = Result.success(Unit)
    override suspend fun sendMagicLink(email: String) = Result.success(Unit)
    override suspend fun verifyMagicLink(email: String, token: String) = Result.success(Unit)
}
