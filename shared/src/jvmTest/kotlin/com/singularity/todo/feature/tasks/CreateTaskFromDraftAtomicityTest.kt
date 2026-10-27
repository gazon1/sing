@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.RoomUnitOfWork
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.database.textIndex
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.core.text.FieldOwnedText
import com.singularity.todo.feature.tasks.data.TaskRepositoryImpl
import com.singularity.todo.feature.tasks.domain.logic.DependencyValidatorImpl
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskFromDraftUseCase
import com.singularity.todo.feature.tasks.domain.usecase.MaterializeRecurringSeriesUseCase
import com.singularity.todo.feature.tasks.presentation.state.DueDateOption
import com.singularity.todo.feature.tasks.presentation.state.TaskDraft
import com.singularity.todo.test.fakes.FakeAttachmentRepository
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.TEST_TZ
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The create door's series materialisation is one transaction.
 *
 * A series-of-one carrying a rule is the forbidden shape FR-001 describes: the
 * launch repair exists to adopt it, but the create door should never leave one
 * behind. The failure is injected by failing the **second** `createSeries` — the
 * one the materialisation writes — so the current occurrence is written first and
 * the rollback is what the test observes.
 */
@Tag("slow")
@Tag("slow:data")
class CreateTaskFromDraftAtomicityTest {

    private val alice = UserId("alice")
    private val today = LocalDate(2026, 6, 15)
    private val clock = FakeClock(Instant.parse("2026-06-15T10:00:00Z"))

    private val tempDirLazy = lazy { Files.createTempDirectory("singularity-create-atomic-").toFile() }
    private val tempDir: File by tempDirLazy
    private val dbLazy = lazy {
        AppDatabaseFactory.build(
            driver = createSqlDriver(),
            dbPath = File(tempDir, "tasks.db").absolutePath,
        )
    }
    private val db: AppDatabase by dbLazy

    @AfterTest
    fun tearDown() {
        if (dbLazy.isInitialized()) db.close()
        if (tempDirLazy.isInitialized()) tempDir.deleteRecursively()
    }

    /**
     * A repository whose window fill fails after the current occurrence is written.
     *
     * Interface delegation keeps every other method the real implementation's, so
     * the test exercises the production write path rather than a reimplementation.
     */
    private class FailSecondSeries(private val delegate: TaskRepository) : TaskRepository by delegate {
        var seriesWrites: Int = 0
            private set

        override suspend fun createSeries(tasks: List<Task>): Result<List<Task>> {
            seriesWrites++
            return if (seriesWrites == 1) {
                delegate.createSeries(tasks)
            } else {
                Result.failure(IllegalStateException("window fill failed"))
            }
        }
    }

    @Test
    fun `a failed window fill leaves no row behind`() = runTest {
        val auth = FakeAuthRepository(Session.Anonymous(alice))
        val currentUser = FakeProfileAwareCurrentUser(auth)
        val unitOfWork = RoomUnitOfWork(db)
        val real = TaskRepositoryImpl(
            taskDao = db.taskDao(),
            textIndex = db.textIndex(currentUser),
            clock = clock,
            currentUser = currentUser,
            syncRepository = FakeSyncRepository(),
            dependencyValidator = DependencyValidatorImpl(db.taskDao(), currentUser),
            unitOfWork = unitOfWork,
        )
        val repo = FailSecondSeries(real)
        val useCase = CreateTaskFromDraftUseCase(
            repo = repo,
            clock = clock,
            timeZone = TEST_TZ,
            currentUser = currentUser,
            checklistRepository = FakeChecklistRepository(),
            attachmentRepository = FakeAttachmentRepository(),
            unitOfWork = unitOfWork,
            materialize = MaterializeRecurringSeriesUseCase(repo, unitOfWork, clock),
        )

        val weekly = RecurrenceSpec.Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.WEEK)
        val result = useCase(
            TaskDraft(
                title = FieldOwnedText.empty().seeded("Buy bread"),
                dueDate = DueDateOption.Custom(today, today.toString()),
                recurrence = weekly,
            ),
        )

        assertTrue(result is Either.Left, "the failure is reported: $result")
        assertEquals(2, repo.seriesWrites, "the current row was written, then the window fill failed")
        advanceUntilIdle()
        assertTrue(
            real.observeAll().first().isEmpty(),
            "nothing was committed: a transaction that swallowed the failure would leave the series of one",
        )
    }
}
