package com.singularity.todo.debug

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.SecureSessionStore
import com.singularity.todo.core.seed.NoteSeed
import com.singularity.todo.core.seed.ProjectSeed
import com.singularity.todo.core.seed.SeedWriter
import com.singularity.todo.core.seed.TaskSeed
import com.singularity.todo.core.wipe.ContentResetter
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.profile.Profile
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.test.stubs.TestAuthStubs
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
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
 * - `task=<title>`           — seeds a task (pair with `due=`). The profile ends
 *   up holding exactly one task with that title: an existing one is updated in
 *   place, a missing one is created. A flow asserts on the row it seeded by
 *   *name* — a task row's test tag is the slug of its title — so the title has
 *   to be the same on every run, and the seed has to be a definition of the
 *   state rather than an append to it. See [SeedWriter].
 * - `desc=<text>`            — sets the description of the seeded task. A flow
 *   that filters by description needs the description to be there before the
 *   filter is applied; the parameter was asked for by such a flow and silently
 *   ignored, which made the flow assert against a task that had none.
 * - `note=<title>`           — seeds a note (one note with that title)
 * - `project=<name>`         — seeds a project (one project with that name)
 * - `profile=<name>`         — creates and switches to a profile
 * - `due=today|tomorrow|-1d|+1d|<YYYY-MM-DD>` — sets dueDate on the seeded task
 * - `updatedAt=now|-22d|<ISO instant>` — stamps the task's created/updated
 *   timestamps. A task stamped "now" is never stagnant (the health window is 21
 *   days without movement), so a flow that needs the *stagnant* bucket has to
 *   seed a task whose timestamps lie in the past.
 * - `blockedBy=<title>` — makes the seeded task depend on an existing task
 *   named `<title>`. A dependency on an incomplete task is what makes a task
 *   *blocked*. The dependency is named by title because a deep link cannot know
 *   an id it has not created itself — seed the dependency first.
 * - `cleanup=<prefix>[,<prefix>…]` — deletes instead of seeding: removes every
 *   task, note and project in the active profile whose title/name starts with
 *   any of the given prefixes. Comma-separated so one link can sweep several
 *   fixture families.
 * - `reset=content` — deletes instead of seeding: removes every row of the active
 *   profile's content — tasks, notes, projects, tags, reminders, attachments,
 *   saved views and the rest, archived, trashed and soft-deleted rows included —
 *   keeping the profile and session. For fixtures a flow *typed* rather than
 *   seeded, which carry no run id for `cleanup=` to match. The list of tables it
 *   reaches, and the ones it leaves alone, are in
 *   `docs/architecture/wipe-inventory.md`. NOT concurrency-safe — it wipes other
 *   runs' in-flight fixtures too.
 * - `session=anonymous|signedout|signedin` — changes the *session*, not the content:
 *   the app is put into a local (anonymous) session, into a signed-out one, or a
 *   session is installed in the app's own encrypted store — the store a real
 *   login writes to, so a flow can have a session that a later step can
 *   invalidate, damage or recover from. A flow can therefore reach the states
 *   the app really has, instead of the one the E2E build happens to start in.
 *   The E2E build replaces the auth repository with a stub that starts
 *   `Session.SignedIn`, which is why the attach-account affordance — rendered
 *   only for `Session.Anonymous` — used to be unreachable in every UI test: a
 *   flow waited for an element that could not be on the screen.
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
 * `cleanup` removes the rows whose title starts with one of the given prefixes.
 * It is the tool for the fixtures a flow *typed* rather than seeded: a seeded
 * fixture carries no run id any more, because the seed replaces and a fixed
 * title is what keeps a flow's assertion — and the failure message of that
 * assertion — readable. The runner wipes the profile's content between flows,
 * so `cleanup` is a mid-run tool rather than the suite's isolation mechanism.
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
            //
            // The task is cleared as well, so the app comes up on its first
            // screen — the agenda — and not on whatever screen it was on. A
            // seed link is a request to put the app in a known state: the
            // flows that use it assert against the agenda, and with the old
            // flags they got whatever the previous flow had left open. Measured
            // on the device: the search flow that follows another search flow
            // never saw `nav_tab_today`, because the app came up on the search
            // screen the previous flow had left it on.
            //
            // CLEAR_TASK clears the task, not the data: the session, the
            // profile and everything else in the database are untouched.
            startActivity(
                Intent(this, com.singularity.todo.MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    )
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

    /**
     * Seeds the one entity the params name. A link seeds at most one kind.
     *
     * The writing itself is [SeedWriter]'s: the activity is the entry point, not
     * the logic, and the rule that matters — the profile ends up holding exactly
     * one row with the seeded title — has to be a rule that a test can hold.
     */
    private suspend fun seedEntity(
        params: Map<String, String>,
        taskRepo: TaskRepository,
        notesRepo: NotesRepository,
        projectsRepo: ProjectsRepository,
        currentUser: ProfileAwareCurrentUser,
    ) {
        val writer = SeedWriter(
            tasks = taskRepo,
            notes = notesRepo,
            projects = projectsRepo,
            currentUser = currentUser,
        )
        when {
            params.containsKey("task") -> seedTask(params, writer)

            params.containsKey("note") -> writer.writeNote(NoteSeed(title = params["note"]!!))

            params.containsKey("project") -> writer.writeProject(
                ProjectSeed(
                    name = params["project"]!!,
                    color = 0xFF2196F3.toInt(), // blue ARGB — deterministic default
                    stamp = Clock.System.now(),
                ),
            )

            // Profile creation and switch are handled by switchToProfileIfRequested.
            params.containsKey("profile") -> Unit
        }
    }

    private suspend fun seedTask(params: Map<String, String>, writer: SeedWriter) {
        val title = params["task"]!!
        // A task stamped "now" is never stagnant: the health window is 21 days
        // without movement, and a flow cannot reach that bucket by seeding a
        // fresh task. `updatedAt=-22d` puts both timestamps in the past, which
        // is what the stagnation bucket is defined by.
        val stamp = params["updatedAt"]?.let { resolveInstant(it) } ?: Clock.System.now()

        val result = writer.writeTask(
            TaskSeed(
                title = title,
                description = params["desc"],
                dueDate = params["due"]?.let { resolveDate(it) },
                stamp = stamp,
                dependsOnTitle = params["blockedBy"],
            ),
        )
        android.util.Log.i("DebugSeedActivity", "task '$title' was ${result.outcome}")
        // A dependency is what makes a task *blocked* — a different bucket from
        // "stale", and one a fresh task can never be. The dependency is named by
        // title, because a deep link cannot know an id it has not created
        // itself; the other task has to be seeded first.
        result.missingDependency?.let { dependencyTitle ->
            android.util.Log.w(
                "DebugSeedActivity",
                "No task titled '$dependencyTitle' to depend on; seeding without a dependency",
            )
        }
    }

    private suspend fun seed(params: Map<String, String>) {
        val taskRepo: TaskRepository by inject()
        val notesRepo: NotesRepository by inject()
        val projectsRepo: ProjectsRepository by inject()
        val profileRepo: ProfileRepository by inject()
        val currentUser: ProfileAwareCurrentUser by inject()

        // Cleanup wins over seeding: a cleanup link carries no entity params, and
        // honouring both would mean a link that deletes the fixture it just made.
        //
        // This dispatch is not an optimisation. The runner sweeps a successful
        // run's fixtures through the `cleanup=` link, so that one run's data
        // cannot satisfy the next run's assertions; without it, every run's
        // entities stayed on the device, and a detekt refactor removed it
        // silently — the methods survived, the branch that called them did not,
        // and nothing failed. A flow that asserts an empty list then failed
        // because of the previous run, and the suite's independence was a
        // claim rather than a fact.
        if (params.containsKey("reset")) {
            resetContent()
            return
        }

        params["cleanup"]?.let { prefixes ->
            cleanup(
                prefixes = prefixes.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                taskRepo = taskRepo,
                notesRepo = notesRepo,
                projectsRepo = projectsRepo,
            )
            return
        }

        // Ensure default profile exists so repositories are functional.
        profileRepo.ensureDefaults()

        // A session link changes who the app thinks it is, and nothing else. It
        // is the same shape as the others: a side effect the flow asks for
        // explicitly, so that the states the app really has — a local session
        // with no account, a signed-out session with the sign-in screen — are
        // reachable from a test, and not only the state the build starts in.
        if (params.containsKey("session")) {
            switchSession(params["session"]!!)
            return
        }

        // Switch to the target profile BEFORE seeding any entity.
        // This updates activeProfileId, which in turn updates
        // ProfileAwareCurrentUser.scopedUserId — the identity the seeded rows are
        // written under.
        switchToProfileIfRequested(params, profileRepo)

        seedEntity(
            params = params,
            taskRepo = taskRepo,
            notesRepo = notesRepo,
            projectsRepo = projectsRepo,
            currentUser = currentUser,
        )
    }

    /**
     * Puts the app into the session a flow asked for.
     *
     * - `anonymous` — a local session with no account: the app is fully usable
     *   and the account section offers "Attach account". The E2E build starts
     *   `SignedIn`, so this is the only way an attach-account flow can reach
     *   the button it is about to click.
     * - `signedout` — no session at all: the sign-in screen is what the app
     *   shows.
     * - `signedin` — a session installed in the app's own encrypted store, the
     *   same write a real login makes, without a server. The app reads it on
     *   its next launch, so a flow that wants a *persisted* session installs it
     *   and restarts the app.
     *
     * The change goes through [AuthRepository], not through the stub's own
     * fields: `startLocalSession()` is the operation the app itself calls when
     * the user chooses to continue offline, so a flow that drives it exercises
     * the real path rather than a back door into the test double.
     */
    private suspend fun switchSession(state: String) {
        val authRepository: AuthRepository by inject()
        when (state.lowercase()) {
            "anonymous", "local" -> {
                val result = authRepository.startLocalSession()
                result.onFailure {
                    android.util.Log.e("DebugSeedActivity", "startLocalSession failed", it)
                }
            }

            "signedout", "signed_out", "none" -> {
                val result = authRepository.signOut()
                result.onFailure {
                    android.util.Log.e("DebugSeedActivity", "signOut failed", it)
                }
            }

            "signedin", "signed_in" -> {
                // The install path: the session goes into the app's own
                // encrypted store, through the same write a real login makes —
                // so the session is really on the device, and a flow can go on
                // to invalidate it, damage the store it lives in, or recover
                // from it.
                val sessionStore: SecureSessionStore by inject()
                TestAuthStubs.installSignedInSession(sessionStore, this@DebugSeedActivity)
                    .onFailure {
                        android.util.Log.e("DebugSeedActivity", "the session could not be installed", it)
                    }
                // And the running app uses it now, without waiting for a
                // restart. In the E2E build the repository is the stub, whose
                // sign-in publishes the session; the restart the helper does
                // afterwards is what proves the app reads it back from the
                // store.
                val result = authRepository.signIn(TestAuthStubs.DEFAULT_EMAIL, "e2e-session")
                result.onFailure {
                    android.util.Log.e("DebugSeedActivity", "the signed-in session was not taken up", it)
                }
            }

            else -> android.util.Log.w(
                "DebugSeedActivity",
                "Unknown session state '$state'; expected 'anonymous', 'signedout' or 'signedin'",
            )
        }
    }

    /**
     * Removes every row of the active profile's content, keeping the profile, the
     * session and the auth state intact.
     *
     * This is the escape hatch for fixtures that a flow creates by *typing* a
     * title rather than seeding it: those carry no run id, so the prefix sweep
     * cannot find them, and they accumulate until a negative flow (one asserting
     * an empty list) starts failing because a previous run's row is still there.
     *
     * The list of tables it reaches lives in the library, not here: the reset is
     * production behaviour that the E2E runner depends on, so it is a port
     * ([ContentResetter]) and the tables it reaches are stated in one place —
     * `ProfileWipePlan` — which `scripts/ci/check-profile-reset-inventory.py` checks
     * against the Room schema and against `docs/architecture/wipe-inventory.md`.
     * A debug activity that carried its own list of tables would be a list
     * nothing checks, and the next table added to the schema would be the one
     * it forgot.
     *
     * The repositories are asked to empty the profile rather than swept row by
     * row, and the difference is the whole point: a sweep of the *live* rows
     * leaves every archived, trashed and soft-deleted row behind, and those are
     * exactly the rows a previous run left — they show up in the next run's
     * trash, or in its archived list, as if the run had never happened.
     *
     * **Not concurrency-safe by construction.** Wiping all content deletes another
     * run's in-flight fixtures too. Use it for sequential runs — or to recover a
     * wedged device — never while two suites share an emulator.
     */
    private suspend fun resetContent() {
        // The type argument is spelled out. A bare `by inject()` leaves the
        // consumer invisible to the orphan-binding audit, which would then read
        // the binding as unwired — and an audit that cannot see the one caller
        // of a binding is an audit that will ask for the binding to be removed.
        val resetter by inject<ContentResetter>()
        val result = resetter.resetContent()
        android.util.Log.i(
            "DebugSeedActivity",
            "reset(content) reached ${result.wipedTables.size} tables: ${result.wipedTables.joinToString()}",
        )
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
