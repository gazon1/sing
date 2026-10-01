package com.singularity.todo.debug

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.singularity.todo.core.error.fold
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.profile.Profile
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.TaskDomain
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.CreateTaskInput
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Debug-only activity that seeds tasks, notes, and projects via deep-link without UI.
 *
 * Deep-link scheme: `todo-debug://seed?<params>`
 *
 * Supported parameters:
 * - `task=<title>`           — creates a task (pair with `due=`)
 * - `note=<title>`           — creates a note
 * - `project=<name>`         — creates a project
 * - `profile=<name>`         — creates and switches to a profile
 * - `due=today|tomorrow|-1d|+1d|<YYYY-MM-DD>` — sets dueDate on the seeded task
 *
 * Examples:
 * ```
 * todo-debug://seed?task=Buy%20milk&due=today
 * todo-debug://seed?note=Meeting%20notes
 * todo-debug://seed?project=Work
 * todo-debug://seed?profile=Personal
 * ```
 *
 * Lives in `src/debug/` — excluded from release APKs.
 * Requires the app to already be running so Koin is initialised.
 */
class DebugSeedActivity : Activity(), KoinComponent {

    override fun onResume() {
        super.onResume()
        val uri = intent?.data ?: return finish()

        val params = parseParams(uri)
        if (params.isEmpty()) return finish()

        try {
            runBlocking { seed(params) }
        } catch (e: Throwable) {
            android.util.Log.e("DebugSeedActivity", "Seed failed", e)
        } finally {
            finish()
        }
    }

    private suspend fun seed(params: Map<String, String>) {
        val taskRepo: TaskRepository by inject()
        val notesRepo: NotesRepository by inject()
        val projectsRepo: ProjectsRepository by inject()
        val profileRepo: ProfileRepository by inject()
        val currentUser: ProfileAwareCurrentUser by inject()

        // Ensure default profile exists so repositories are functional.
        profileRepo.ensureDefaults()

        val userId = currentUser.current

        when {
            params.containsKey("task") -> {
                val title = params["task"]!!
                val dueDate = params["due"]?.let { resolveDate(it) }
                val now = Clock.System.now()

                val input: CreateTaskInput = TaskDomain.createInput(
                    title = title,
                    kind = TaskKind.Task,
                    dueDate = dueDate,
                ).fold(
                    left = { error -> throw IllegalArgumentException("Invalid input: $error") },
                    right = { it },
                )

                val task = TaskDomain.buildTask(
                    input = input,
                    id = TaskId.generate(),
                    createdAt = now,
                    updatedAt = now,
                    userId = userId,
                )

                taskRepo.upsert(task)
            }
            params.containsKey("note") -> {
                val title = params["note"]!!
                notesRepo.createNoteWithTitle(title)
            }
            params.containsKey("project") -> {
                val name = params["project"]!!
                val now = Clock.System.now()

                val project = Project(
                    id = ProjectId.generate(),
                    name = name,
                    color = 0xFF2196F3.toInt(), // blue ARGB — deterministic default
                    description = null,
                    createdAt = now,
                    updatedAt = now,
                    userId = userId,
                )

                projectsRepo.create(project)
            }
            params.containsKey("profile") -> {
                val name = params["profile"]!!
                profileRepo.ensureDefaults()
                val now = Clock.System.now()

                val profile = Profile(
                    id = ProfileId.generate(),
                    name = name,
                    emoji = "🔹",
                    colorIdx = 0,
                    isDefault = false,
                    createdAt = now,
                    updatedAt = now,
                )

                profileRepo.create(profile)
                profileRepo.switchTo(profile.id)
            }
        }
    }

    private fun parseParams(uri: Uri): Map<String, String> {
        return uri.encodedQuery
            ?.split("&")
            ?.mapNotNull { part ->
                val kv = part.split("=", limit = 2)
                if (kv.size == 2) {
                    val key = Uri.decode(kv[0])
                    val value = Uri.decode(kv[1])
                    if (key.isNotBlank() && value.isNotBlank()) key to value else null
                } else null
            }
            ?.toMap()
            ?: emptyMap()
    }

    /**
     * Resolves a date expression to [LocalDate].
     *
     * Supported:
     * - `today`     — current date in the device time zone
     * - `tomorrow`  — next calendar day
     * - `-1d`, `+1d` — relative days from today (negative = past)
     * - `YYYY-MM-DD` — exact ISO date
     */
    private fun resolveDate(expr: String): LocalDate {
        val today = Clock.System.now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date

        return when (expr.lowercase()) {
            "today" -> today
            "tomorrow" -> LocalDate.fromEpochDays(today.toEpochDays() + 1)
            else -> {
                val relMatch = Regex("^([+-]?)(\\d+)d$").matchEntire(expr.lowercase())
                if (relMatch != null) {
                    val sign = if (relMatch.groupValues[1] == "-") -1L else 1L
                    val days = relMatch.groupValues[2].toLong() * sign
                    LocalDate.fromEpochDays(today.toEpochDays() + days)
                } else {
                    // Assume YYYY-MM-DD
                    LocalDate.parse(expr)
                }
            }
        }
    }
}
