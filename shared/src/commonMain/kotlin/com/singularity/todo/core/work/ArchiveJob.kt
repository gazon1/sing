package com.singularity.todo.core.work

import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/**
 * Background job that auto-archives completed tasks older than the configured retention window.
 * Runs once per day. Idempotent — archivable tasks are a monotonic set.
 */
class ArchiveJob(
    private val taskDao: TaskDao,
    private val settings: SettingsRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : BackgroundJob {
    override val id: String = ID

    override suspend fun run(): JobOutcome = runCatching {
        val retentionDays = settings.autoArchiveRetentionDays.first()
        if (retentionDays <= 0) return@runCatching

        // Compute cutoff without using Duration arithmetic (kotlin.time.Duration - Instant
        // interop varies across platforms). Use epoch milliseconds directly.
        val nowMillis = System.currentTimeMillis()
        val cutoffMillis = nowMillis - (retentionDays.toLong() * 86_400_000L)
        val userId = currentUser.current.value

        taskDao.archiveCompletedForUser(cutoffMillis, userId)
    }.fold(
        onSuccess = { JobOutcome.Success },
        onFailure = { JobOutcome.Failed(it) },
    )

    companion object {
        const val ID = "archive"
    }
}
