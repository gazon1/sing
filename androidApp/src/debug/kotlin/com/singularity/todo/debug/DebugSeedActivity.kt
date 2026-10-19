package com.singularity.todo.debug

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.singularity.todo.core.error.fold
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.profile.Profile
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.core.ids.UserId
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
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
 * - `updatedAt=now|-22d|<ISO instant>` — stamps the task's created/updated
 *   timestamps. A task stamped "now" is never stagnant (the health window is 21
 *   days without movement), so a flow that needs the *stagnant* bucket has to
 *   seed a task whose timestamps lie in the past.
 * - `dependsOn=<title>` — makes the seeded task depend on an existing task
 *   named `<title>`. A dependency on an incomplete task is what makes a task
 *   *blocked*. The dependency is named by title because a deep link cannot know
 *   an id it has not created itself — seed the dependency first.
 * - `cleanup=<prefix>[,<prefix>…]` — deletes instead of seeding: removes every
 *   task, note and project in the active profile whose title/name starts with
 *   any of the given prefixes. Comma-separated so one link can sweep several
 *   fixture families.
 * - `reset=content` — deletes instead of seeding: removes every task, note and
 *   project in the active profile, keeping the profile and session. For fixtures
 *   a flow *typed* rather than seeded, which carry no run id for `cleanup=` to
 *   match. NOT concurrency-safe — it wipes other runs' in-flight fixtures too.
 *
 * Examples:
 * ```
 * todo-debug://seed?task=Buy%20milk&due=today
 * todo-debug://seed?note=Meeting%20notes
 * todo-debug://seed?project=Work
 * todo-debug://seed?profile=Personal
 * todo-debug://seed?cleanup=r1a2b3c,Buy%20milk
 * ```
 *
 * `cleanup` is how a flow's fixtures are tied to the run that created them:
 * the runner stamps a per-flow id into the seeded titles and passes the same id
 * back here once the flow passes, so one run can never delete another run's data
 * and nothing is left behind to collide with the next run. Cleaning on *success*
 * only is deliberate — a failing flow keeps its fixtures for triage.
 *
 * Cleanup and seeding are mutually exclusive: `cleanup` short-circuits the
 * entity-creating branches below.
 *
 * Lives in `src/debug/` — excluded from release APKs.
 * Works warm or cold: Koin is started by the Application onCreate, and after
 * seeding the activity always brings [com.singularity.todo.MainActivity] up
 * (CLEAR_TOP|SINGLE_TOP), whether it was already beneath us or not.
 */
class DebugSeedActivity :
    Activity(),
    KoinComponent {

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
            // Bring the real app up in every case. When the seed link arrived
            // warm, MainActivity is already beneath us and CLEAR_TOP|SINGLE_TOP
            // just re-activates it; when the link cold-started the process, we
            // are the only activity in the task and finishing alone would dump
            // the user (and the UI test) on the home screen.
            startActivity(
                Intent(this, com.singularity.todo.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
            )
            finish()
        }
    }

    /**
     * The profile the entities must land in, switched before anything is written.
     *
     * Split out of [seed] because it is the one part of seeding that is not a single
     * entity write: `switchTo` moves `activeProfileId`, which is what
     * `ProfileAwareCurrentUser.scopedUserId` resolves against, so it has to happen
     * before the first insert rather than alongside the others.
     */
    private suspend fun switchToProfileIfRequested(
        params: Map<String, String>,
        profileRepo: ProfileRepository,
    ) {
        if (!params.containsKey("profile")) return
        val profileName = params["profile"]!!
        val existing = profileRepo.findByName(profileName)
        if (existing != null) {
            profileRepo.switchTo(existing.id)
            return
        }
        val now = Clock.System.now()
        val newProfile = Profile(
            id = ProfileId.generate(),
            name = profileName,
            emoji = "🔹",
            colorIdx = 0,
            isDefault = false,
            createdAt = now,
            updatedAt = now,
        )
        profileRepo.create(newProfile)
        profileRepo.switchTo(newProfile.id)
    }

    /** Seeds the one entity the params name. A link seeds at most one kind. */
    private suspend fun seedEntity(
        params: Map<String, String>,
        userId: UserId,
        taskRepo: TaskRepository,
        notesRepo: NotesRepository,
        projectsRepo: ProjectsRepository,
    ) {
        when {
            params.containsKey("task") -> seedTask(params, userId, taskRepo)

            params.containsKey("note") -> notesRepo.createNoteWithTitle(params["note"]!!)

            params.containsKey("project") -> seedProject(params, userId, projectsRepo)

            // Profile creation and switch are handled by switchToProfileIfRequested.
            params.containsKey("profile") -> Unit
        }
    }

    private suspend fun seedTask(
        params: Map<String, String>,
        userId: UserId,
        taskRepo: TaskRepository,
    ) {
        val title = params["task"]!!
        val dueDate = params["due"]?.let { resolveDate(it) }
        // A task stamped "now" is never stagnant: the health window is 21 days
        // without movement, and a flow cannot reach that bucket by seeding a
        // fresh task. `updatedAt=-22d` puts both timestamps in the past, which
        // is what the stagnation bucket is defined by.
        val stamp = params["updatedAt"]?.let { resolveInstant(it) } ?: Clock.System.now()

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
            createdAt = stamp,
            updatedAt = stamp,
            userId = userId,
        )
        taskRepo.upsert(task)

        // A dependency is what makes a task *blocked* — a different bucket from
        // "stale", and one a fresh task can never be. The dependency is named by
        // title, because a deep link cannot know an id it has not created
        // itself; the other task has to be seeded first.
        params["dependsOn"]?.let { dependencyTitle ->
            val dependency = taskRepo.observeAll().first()
                .firstOrNull { it.title == dependencyTitle }
            if (dependency == null) {
                android.util.Log.w(
                    "DebugSeedActivity",
                    "No task titled '$dependencyTitle' to depend on; seeding without a dependency",
                )
            } else {
                taskRepo.setDependencies(task.id, setOf(dependency.id))
            }
        }
    }

    private suspend fun seedProject(
        params: Map<String, String>,
        userId: UserId,
        projectsRepo: ProjectsRepository,
    ) {
        val now = Clock.System.now()
        projectsRepo.create(
            Project(
                id = ProjectId.generate(),
                name = params["project"]!!,
                color = 0xFF2196F3.toInt(), // blue ARGB — deterministic default
                description = null,
                createdAt = now,
                updatedAt = now,
                userId = userId,
            ),
        )
    }

    private suspend fun seed(params: Map<String, String>) {
        val taskRepo: TaskRepository by inject()
        val notesRepo: NotesRepository by inject()
        val projectsRepo: ProjectsRepository by inject()
        val profileRepo: ProfileRepository by inject()
        val currentUser: ProfileAwareCurrentUser by inject()

        // Ensure default profile exists so repositories are functional.
        profileRepo.ensureDefaults()

        // Switch to the target profile BEFORE seeding any entity.
        // This updates activeProfileId, which in turn updates
        // ProfileAwareCurrentUser.scopedUserId — used by createNoteWithTitle.
        switchToProfileIfRequested(params, profileRepo)

        // Use the scoped userId for all entity writes. When profile= was given,
        // switchTo above updated activeProfileId so currentUser.current returns
        // the correct scoped id. When no profile= was given, this is the current
        // profile's scoped userId.
        seedEntity(
            params = params,
            userId = currentUser.current,
            taskRepo = taskRepo,
            notesRepo = notesRepo,
            projectsRepo = projectsRepo,
        )
    }

    /**
     * Removes every task, note and project in the active profile, keeping the
     * profile, the session and the auth state intact.
     *
     * This is the escape hatch for fixtures that a flow creates by *typing* a
     * title rather than seeding it: those carry no run id, so the prefix sweep
     * cannot find them, and they accumulate until a negative flow (one asserting
     * an empty list) starts failing because a previous run's row is still there.
     *
     * **Not concurrency-safe by construction.** Wiping all content deletes another
     * run's in-flight fixtures too. Use it for sequential runs — or to recover a
     * wedged device — never while two suites share an emulator.
     */
    private suspend fun resetContent(
        taskRepo: TaskRepository,
        notesRepo: NotesRepository,
        projectsRepo: ProjectsRepository,
    ) {
        var removed = 0
        taskRepo.observeAll().first().forEach { taskRepo.delete(it.id).onSuccess { removed++ } }
        notesRepo.observeAll().first().forEach { notesRepo.delete(it.id).onSuccess { removed++ } }
        projectsRepo.observeAll().first().forEach { projectsRepo.delete(it.id).onSuccess { removed++ } }
        android.util.Log.i("DebugSeedActivity", "reset(content) removed $removed entities")
    }

    /**
     * Removes every task, note and project in the active profile whose
     * title/name starts with one of [prefixes].
     *
     * Matching is a prefix test rather than an exact-title test on purpose: a
     * run stamps its id onto the *start* of each seeded title ("r1a2b3 Buy
     * milk"), so one prefix sweep catches the run's whole fixture set and its
     * variants in a single pass.
     *
     * Scoped to the active profile by construction — all three repositories
     * resolve against the current user, so a cleanup can never reach another
     * profile's data. That also means a flow that switched profiles via
     * `profile=` must be cleaned while that profile is still active.
     */
    private suspend fun cleanup(
        prefixes: List<String>,
        taskRepo: TaskRepository,
        notesRepo: NotesRepository,
        projectsRepo: ProjectsRepository,
    ) {
        if (prefixes.isEmpty()) return
        fun matches(candidate: String) = prefixes.any { candidate.startsWith(it) }

        // observeAll() is user-scoped and drops deleted rows, so a single
        // first() gives exactly the live fixture set for the active profile.
        var removed = 0
        taskRepo.observeAll().first().filter { matches(it.title) }.forEach {
            taskRepo.delete(it.id).onSuccess { removed++ }
        }
        notesRepo.observeAll().first().filter { matches(it.title) }.forEach {
            notesRepo.delete(it.id).onSuccess { removed++ }
        }
        projectsRepo.observeAll().first().filter { matches(it.name) }.forEach {
            projectsRepo.delete(it.id).onSuccess { removed++ }
        }
        android.util.Log.i("DebugSeedActivity", "cleanup($prefixes) removed $removed entities")
    }

    private fun parseParams(uri: Uri): Map<String, String> = uri.encodedQuery
        ?.split("&")
        ?.mapNotNull { part ->
            val kv = part.split("=", limit = 2)
            if (kv.size == 2) {
                val key = Uri.decode(kv[0])
                val value = Uri.decode(kv[1])
                if (key.isNotBlank() && value.isNotBlank()) key to value else null
            } else {
                null
            }
        }
        ?.toMap()
        ?: emptyMap()

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

    /**
     * Resolves an instant expression to a point in time.
     *
     * Supported:
     * - `now`     — the current instant
     * - `-22d`    — that many days in the past (the sign is what matters; the
     *   past is the only direction that matters for the stagnation bucket)
     * - `<ISO-8601>` — exact instant, e.g. `2026-09-01T00:00:00Z`
     *
     * The past is what a stale task needs: the health window is 21 days
     * without movement, so a task stamped "now" is never stagnant, no matter
     * how the flow sets its due date.
     */
    private fun resolveInstant(expr: String): Instant {
        val now = Clock.System.now()
        return when (expr.lowercase()) {
            "now" -> now

            else -> {
                val relMatch = Regex("^([+-]?)(\\d+)d$").matchEntire(expr.lowercase())
                if (relMatch != null) {
                    // Same shape as [resolveDate]: the sign is the direction, and
                    // the offset is *added* to now. Subtracting a negative offset
                    // stamps the task in the future — and a task in the future is
                    // not stagnant, which is the opposite of what the flow asked
                    // for, and looks exactly like a seed that did nothing.
                    val sign = if (relMatch.groupValues[1] == "-") -1L else 1L
                    val days = relMatch.groupValues[2].toLong() * sign
                    now + days.days
                } else {
                    Instant.parse(expr)
                }
            }
        }
    }
}
