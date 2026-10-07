package com.singularity.todo.feature.proposals.data

import co.touchlab.kermit.Logger
import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.RecordingCrashReportingPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A status that was not recomputed is reported (REQ-PROP-001).
 *
 * ## What this is a control for
 *
 * `ApplyProposalItemUseCase` calls `proposals.refreshStatus(...)` after every
 * confirm, reject and batch confirm — three sites — and discards the `Result` at all
 * three. `refreshStatus` is the only thing that recomputes a proposal's aggregate
 * status from its items, so when it fails the proposal keeps showing the status it had
 * *before* the items changed: the user confirmed three items and the proposal still
 * reads "pending".
 *
 * The underlying items are correct in the database. This is a derived value that did
 * not get derived, which is why it is reported rather than thrown: throwing would fail
 * a confirm that genuinely succeeded, over a summary row.
 *
 * ## Why the seam and not the three call sites
 *
 * The same reasoning as REQ-OS-028 on `SyncEngine.enqueue`, and for the same reason:
 * three identical calls in one class, each already handling the `Result` of the write
 * immediately before it. Putting the error path in each caller would repeat the sentence
 * three times and still be silent at the next call site someone writes. `refreshStatus`
 * is the one place that knows the recomputation did not happen.
 *
 * The alternative — dropping the reporting and accepting a stale status — was not
 * chosen because "stale forever" is indistinguishable from "the proposal is genuinely
 * still pending" in the UI, and only one of those is true.
 *
 * ## What this does not prove
 *
 * That the derived status converges if `refreshStatus` keeps failing. It does not, and
 * no test here claims it does: the failure is now visible, and the next confirm or
 * reject retries the recomputation.
 */
@Tag("fast")
class ProposalRepositoryRefreshStatusTest {

    private val clock = com.singularity.todo.test.fakes.FakeClock()
    private val log = Logger.withTag("ProposalRepositoryRefreshStatusTest")
    private val userId = UserId("test-user")

    /**
     * A DAO whose status update refuses, which is what a closed database does.
     *
     * Delegating rather than reimplementing: [ProposalDao] has more members than this
     * test cares about, and a hand-written stub of them would go stale silently.
     */
    private class UnwritableStatusDao(private val delegate: ProposalDao) : ProposalDao by delegate {
        var updateAttempts: Int = 0
        var rejectUpdates: Boolean = true

        override suspend fun updateProposalStatus(
            id: String,
            status: String,
            updatedAt: Long,
            userId: String,
        ): Int {
            updateAttempts++
            return if (rejectUpdates) {
                throw IllegalStateException("database is closed")
            } else {
                delegate.updateProposalStatus(id, status, updatedAt, userId)
            }
        }
    }

    /** Only [getItemsForProposal] and [updateProposalStatus] are on this test's path. */
    private class EmptyProposalDao : ProposalDao {
        override suspend fun upsertProposal(entity: AiProposalEntity) = Unit
        override suspend fun getProposal(id: String): AiProposalEntity? = null
        override fun watchProposal(id: String): Flow<AiProposalEntity?> = flowOf(null)
        override fun watchProposalsForTarget(
            targetId: String,
            targetKind: String,
            userId: String,
        ): Flow<List<AiProposalEntity>> = flowOf(emptyList())

        override fun watchProposalsByTargetKind(
            userId: String,
            targetKind: String,
            status: String,
        ): Flow<List<AiProposalEntity>> = flowOf(emptyList())

        override fun watchProposalsByStatus(
            userId: String,
            status: String,
        ): Flow<List<AiProposalEntity>> = flowOf(emptyList())

        override suspend fun updateProposalStatus(
            id: String,
            status: String,
            updatedAt: Long,
            userId: String,
        ): Int = 1
    }

    private class EmptyProposalItemDao : ProposalItemDao {
        override suspend fun upsertItem(entity: ProposalItemEntity) = Unit
        override suspend fun getItem(id: String): ProposalItemEntity? = null
        override fun watchItemsForProposal(proposalId: String): Flow<List<ProposalItemEntity>> =
            flowOf(emptyList())

        override suspend fun getItemsForProposal(proposalId: String): List<ProposalItemEntity> =
            emptyList()

        override suspend fun countItemsWithFingerprint(proposalId: String, fingerprint: String): Int = 0

        override suspend fun claimItem(
            userId: String,
            id: String,
            status: String,
            decidedAt: Long,
            actor: String,
            reason: String?,
        ): Int = 0

        override suspend fun retractPendingItems(proposalId: String, userId: String): Int = 0

        override suspend fun rejectedFingerprints(userId: String, limit: Int): List<String> = emptyList()
    }

    private fun repository(
        dao: ProposalDao,
        items: ProposalItemDao,
        crashReporter: CrashReportingPort,
    ) = ProposalRepositoryImpl(
        dao = dao,
        items = items,
        clock = clock,
        currentUser = FakeProfileAwareCurrentUser(),
        log = log,
        crashReporter = crashReporter,
    )

    @Test
    fun `a status that was not recomputed is reported`() = runTest {
        val reporter = RecordingCrashReportingPort()
        val dao = UnwritableStatusDao(EmptyProposalDao())
        val repository = repository(dao, EmptyProposalItemDao(), reporter)

        val result = repository.refreshStatus(ProposalId("p-1"), userId)

        assertTrue(result.isFailure, "the caller still gets a failure to react to")
        assertEquals(
            listOf("proposals.refresh_status_failed"),
            reporter.reports.map { it.second },
            "the issue key is the stable handle an operator greps for",
        )
        assertTrue(
            dao.updateAttempts == 1,
            "and the recomputation really was attempted, so the report is about the write " +
                "failing rather than about refreshStatus never running",
        )
    }

    @Test
    fun `a status that was recomputed reports nothing`() = runTest {
        // The negative case. Without it, an implementation that reported unconditionally
        // would pass the control above and bury real failures in noise on the happy path,
        // which is the overwhelming majority of calls.
        val reporter = RecordingCrashReportingPort()
        val dao = UnwritableStatusDao(EmptyProposalDao()).apply { rejectUpdates = false }
        val repository = repository(dao, EmptyProposalItemDao(), reporter)

        val result = repository.refreshStatus(ProposalId("p-1"), userId)

        assertTrue(result.isSuccess)
        assertEquals(
            emptyList(),
            reporter.reports,
            "the happy path is the overwhelming majority of calls; reporting it would bury " +
                "the failures this requirement exists to surface",
        )
    }
}
