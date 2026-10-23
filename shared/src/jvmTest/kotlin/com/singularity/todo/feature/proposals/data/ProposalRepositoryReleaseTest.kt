package com.singularity.todo.feature.proposals.data

import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.DecidedActor
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus
import com.singularity.todo.feature.proposals.domain.model.TaskValue
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The release of a claim, against the real SQL.
 *
 * ## Why this is a test and not a fake
 *
 * The release is a compare-and-set written as SQL: `status = 'Confirmed'` **and**
 * `decided_actor = 'User'` **and** the proposal belongs to the user. Every literal in
 * that statement is a chance to spell a column wrong, and a wrong literal does not
 * fail a test — it fails a *user's* change: the release matches no row, the item stays
 * `Confirmed`, and the app shows a change as done that was never applied.
 *
 * The fakes cannot catch that, because they do not run the SQL. So this test runs
 * against a real Room database on a throwaway file.
 *
 * Slow (~2-3 s): it spins up Room, creates the schema and runs SQL.
 */
@Tag("slow")
class ProposalRepositoryReleaseTest {

    private val user = UserId("release-user")
    private val other = UserId("someone-else")

    private val tempDirLazy = lazy { Files.createTempDirectory("singularity-proposal-release-").toFile() }
    private val tempDir: File by tempDirLazy

    private val dbLazy = lazy {
        AppDatabaseFactory.build(
            driver = createSqlDriver(),
            dbPath = File(tempDir, "proposals.db").absolutePath,
        )
    }
    private val db: AppDatabase by dbLazy

    @AfterTest
    fun tearDown() {
        if (dbLazy.isInitialized()) db.close()
        if (tempDirLazy.isInitialized()) tempDir.deleteRecursively()
    }

    private fun repository(forUser: UserId) = ProposalRepositoryImpl(
        dao = db.proposalDao(),
        items = db.proposalItemDao(),
        clock = Clock.System,
        currentUser = FakeProfileAwareCurrentUser(forUser),
    )

    @Test
    fun `a confirmed change goes back to waiting`() = runTest {
        val repo = repository(user)
        val itemId = seed(repo, user)
        repo.claim(itemId, ProposalItemStatus.Confirmed, DecidedActor.User, null, user)

        val released = repo.release(itemId, user)

        assertTrue(released.isSuccess, "the release of a confirmed change succeeds: $released")
        assertEquals(
            ProposalItemStatus.Pending,
            repo.getItem(itemId)?.status,
            "the change is waiting again, because it was never applied",
        )
    }

    @Test
    fun `a change that was never confirmed is not put back`() = runTest {
        val repo = repository(user)
        val itemId = seed(repo, user)

        val released = repo.release(itemId, user)

        assertTrue(released.isFailure, "there was nothing to put back: $released")
        assertEquals(ProposalItemStatus.Pending, repo.getItem(itemId)?.status)
    }

    @Test
    fun `a change of another user is not put back`() = runTest {
        val owner = repository(user)
        val itemId = seed(owner, user)
        owner.claim(itemId, ProposalItemStatus.Confirmed, DecidedActor.User, null, user)

        val released = repository(other).release(itemId, other)

        assertTrue(released.isFailure, "the change is not the other user's to put back: $released")
        assertEquals(
            ProposalItemStatus.Confirmed,
            owner.getItem(itemId)?.status,
            "the change stays confirmed for the user who confirmed it",
        )
    }

    @Test
    fun `a refused change is not put back`() = runTest {
        val repo = repository(user)
        val itemId = seed(repo, user)
        repo.claim(itemId, ProposalItemStatus.Rejected, DecidedActor.User, "not now", user)

        val released = repo.release(itemId, user)

        assertTrue(released.isFailure, "a refusal is not a claim: $released")
        assertEquals(ProposalItemStatus.Rejected, repo.getItem(itemId)?.status)
    }

    @Test
    fun `a change is put back after the profile switched`() = runTest {
        val owner = repository(user)
        val itemId = seed(owner, user)
        owner.claim(itemId, ProposalItemStatus.Confirmed, DecidedActor.User, null, user)

        // The profile switched between the claim and the release: the screen moved on,
        // or the user opened another profile. The guard is the user's — the row still
        // belongs to the user who confirmed it — so the change goes back to waiting
        // even though the current profile is somebody else's.
        val released = repository(other).release(itemId, user)

        assertTrue(released.isSuccess, "the release belongs to the user who confirmed it: $released")
        assertEquals(
            ProposalItemStatus.Pending,
            owner.getItem(itemId)?.status,
            "the change waits again, because it was never applied",
        )
    }

    /** Stores a proposal with one waiting change, and returns the change's id. */
    private suspend fun seed(repo: ProposalRepositoryImpl, owner: UserId): ProposalItemId {
        val proposalId = ProposalId.generate()
        val itemId = ProposalItemId.generate()
        repo.save(
            AiProposal(
                id = proposalId,
                targetKind = AiProposal.TARGET_KIND_TASK,
                targetId = "task-1",
                userId = owner,
                source = ProposalSource.Agent,
                status = ProposalStatus.Pending,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
                items = listOf(
                    ProposalItem(
                        id = itemId,
                        proposalId = proposalId,
                        kind = ProposalItemKind.SetTaskField(TaskValue.Title("Buy milk")),
                        targetId = "task-1",
                        humanSummary = "Set the title to «Buy milk»",
                        status = ProposalItemStatus.Pending,
                        fingerprint = "fingerprint-1",
                    ),
                ),
            ),
        ).getOrThrow()
        return itemId
    }
}
