package com.singularity.todo.debug

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.SecureSessionManager
import com.singularity.todo.core.seed.NoteSeed
import com.singularity.todo.core.seed.ProjectSeed
import com.singularity.todo.core.seed.SeedArrival
import com.singularity.todo.core.seed.SeedCount
import com.singularity.todo.core.seed.SeedFailure
import com.singularity.todo.core.seed.SeedLinkKeys
import com.singularity.todo.core.seed.SeedLinkLifecycle
import com.singularity.todo.core.seed.SeedWriter
import com.singularity.todo.core.seed.TaskSeed
import com.singularity.todo.core.wipe.ContentResetter
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.profile.Profile
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.stubs.TestAuthStubs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

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
 *   ignored, which made the flow assert against a task that had none. On a note
 *   it is the note's body instead — a note with a title and no text is an empty
 *   screen.
 * - `priority=<level>`       — sets the priority of the seeded task: `none`,
 *   `low`, `medium`, `high` or `urgent`. A level that is not one of them fails
 *   the seed rather than seeding a task with a priority nobody asked for.
 * - `note=<title>`           — seeds a note (one note with that title)
 * - `linkTo=<title>`         — makes the seeded note's body link to the task with
 *   that title. The link is written the way the app writes one — an anchor whose
 *   href is `task://<id>` — so the note is one the editor could have produced, and
 *   the link machinery reads it as a link. The target is named rather than
 *   addressed by id, because the caller cannot know the id the app gave the task
 *   it seeded a moment earlier.
 * - `id=<id>` — the id the seeded row carries. A seed that names an id owns it:
 *   the row the flow addresses by that id is the row the seed writes. It is what
 *   makes a deep link addressable — `singularity://task/<id>` names an id, and a
 *   flow cannot know the id the app generated. A project named by that id is
 *   updated in place, so a seed that names an id that is already there renames
 *   it: a rename is a seed that owns the row and gives it another name.
 * - `project=<name>`         — seeds a project (one project with that name)
 * - `inProject=<name>`       — puts the seeded task in the project with that
 *   name. The name, not the id: the link that seeds the task cannot know the id
 *   the app gave the project. A project that is not there fails the seed — a
 *   task in no project is not the task the fixture asked for.
 * - `parent=<project id>`    — hangs the seeded project below the project with
 *   that id, for a nested fixture. The parent is named by id because the link
 *   that seeds the child cannot know a generated id. A parent that is not
 *   there fails the seed: nothing is written, and the activity shows the
 *   reason instead of bringing the app up — a project that hangs below
 *   nothing is a root, and a root is the row the Android menu lists.
 * - `projects=<count>`       — seeds that many projects at once, named
 *   `Bulk 001`, `Bulk 002`, … A profile with a hundred projects is a fixture —
 *   the menu's folded Projects section exists for it — and a hundred deep
 *   links are not a way to make one. The names share a prefix, so
 *   `cleanup=Bulk` sweeps them again.
 * - `profile=<name>`         — creates and switches to a profile. The switch
 *   happens *before* anything is written or deleted, so a link that names a
 *   profile writes into it — and a link that names one and resets wipes that
 *   profile and nothing else. The demo dataset uses a profile of its own for
 *   exactly that reason.
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
 *   keeping the profile and session. The *active* profile is the one the link's
 *   own `profile=` switch named, so a link that says `profile=X&reset=content`
 *   wipes X and nothing else. For fixtures a flow *typed* rather than
 *   seeded, which carry no run id for `cleanup=` to match. The list of tables it
 *   reaches, and the ones it leaves alone, are in
 *   `docs/architecture/wipe-inventory.md`. The seeds run one at a time, so a
 *   wipe does not race another seed's writes — but it does wipe the rows a
 *   flow typed itself.
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
 * a successful seed the activity always brings
 * [com.singularity.todo.MainActivity] up (CLEAR_TOP|SINGLE_TOP), whether it was
 * already beneath us or not. A seed that failed stays on its own screen with
 * the reason on it.
 *
 * A link always reaches this activity: it is declared `singleTask`, and
 * [onNewIntent] takes the link that arrives at a live instance. Without both,
 * the system delivers a link whose data matches the app task's base intent to
 * the running MainActivity instead — a link that is then lost without a word.
 *
 * A link that arrives while a seed is still running is seeded too, with its own
 * parameters: it waits for the running seed to finish, so the seeds never write
 * at the same time. The newest link owns the screen, and the older one keeps
 * writing its rows without taking the screen away from it.
 *
 * The seeds run in the order the links *arrived*, not in the order their
 * workers happen to start: a link that arrived first writes first even when its
 * worker reaches its gate last. The data on the device is therefore the data of
 * the link that owns the screen.
 */
class DebugSeedActivity :
    Activity(),
    KoinComponent {
    /**
     * The seed links that reached this activity, and the order they ran in.
     *
     * One seed at a time, one arrival per link, and the newest arrival owns
     * the screen — the rules live in [SeedLinkLifecycle] and the sequencer
     * behind it, so that a test can hold them without an Android harness.
     */
    private val links = SeedLinkLifecycle<Intent>()

    /**
     * Takes a link that arrived at a live instance.
     *
     * The activity is `singleTask` (see the debug manifest), so a link that
     * reaches a running instance arrives here instead of being delivered to
     * whatever sits on top of the task. The default implementation of
     * [Activity.onNewIntent] ignores the new intent — the activity would keep
     * the old one, and the link would be lost as silently as it is when the
     * system hands it to another activity.
     *
     * The link is seeded *here*, and not left to [onResume]: a link that
     * reaches an instance which is already resumed does not come with another
     * [onResume] — the activity is not restarted for it — so a link handled
     * only in [onResume] was simply dropped. Measured on the device: a second
     * seed link that arrived while the first seed was still running left no
     * trace at all, and the first seed's completion brought the app up on its
     * own terms.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        links.onNewIntent(intent)
        handleSeedLink()
    }

    /**
     * Takes the link the activity was started with.
     *
     * The launch link arrives here, and it has to be handled here: a link that
     * reaches the activity while it is still starting arrives through
     * [onNewIntent], and [setIntent] then replaces the link this activity was
     * started with — the resume that would have seeded it sees the *new* link
     * instead, and the launch link is never seeded at all. Measured on the
     * device: a link that arrived three seconds after the first one, while the
     * app was still starting, seeded the second link and dropped the first
     * one without a word.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        links.onCreate(intent)
        handleSeedLink()
    }

    override fun onResume() {
        super.onResume()
        handleSeedLink()
    }

    /**
     * Seeds what the link asks for — once per link, one seed at a time.
     *
     * The link is handled where it arrives, so a link that reaches a live
     * instance is seeded even when no [onResume] follows it. The same link
     * instance comes back through [onResume] right after [onNewIntent] set it,
     * and that second visit is not a second seed: a link is a definition of the
     * state, but running it twice would write it twice.
     *
     * A link that arrives while a seed is still running is seeded too, with
     * its own parameters — it waits for the running seed to finish first. Two
     * seeds writing at the same time leave a profile that is neither one's
     * state: rows written over each other, and a `reset=content` that wipes
     * the rows the other seed is still writing. So the seeds run one at a
     * time, and the newest arrival owns the screen: the older run keeps
     * writing its own rows, but it does not bring the app up and does not show
     * its failure, because the newer launch owns those.
     *
     * The order is the order of the *arrivals*, not the order in which the
     * workers happen to start: a worker that starts late still writes after the
     * links that arrived before it, so the data on the device is the data of the
     * link that owns the screen.
     */
    private fun handleSeedLink() {
        val link = links.current() ?: return finish()
        val uri = link.data ?: return finish()

        val params = parseParams(uri)
        if (params.isEmpty()) return finish()

        // Null when this link was handled already: the arrival is recorded by
        // the link's identity, so the resume that follows the arrival is not
        // a second seed.
        val arrival: SeedArrival = links.arrive() ?: return

        SeedLinkKeys.unknown(params.keys).forEach { key ->
            android.util.Log.w(
                "DebugSeedActivity",
                "Unknown seed query key '$key'; it was ignored",
            )
        }

        Thread {
            try {
                // The gate is the whole of the ordering: this seed does not
                // start before the seed of the link that arrived before it has
                // finished, and the gate is opened when the seed fails.
                runBlocking { links.run(arrival) { seed(params) } }
            } catch (e: Throwable) {
                // One line with the reason in it. The message is what a runner
                // greps for: the stack trace below it is for a reader who is
                // already looking at the code.
                android.util.Log.e(
                    "DebugSeedActivity",
                    "${SeedFailure.PREFIX}${e.message ?: e.javaClass.simpleName}",
                    e,
                )
                // A failed seed does not bring the app up. The fixture the flow
                // asked for is not there, so the flow has to fail *here* — at the
                // seed — instead of later, on an assertion about a row that was
                // never written. The reason is on the screen, so the failure
                // reads without the logcat.
                //
                // A run that no longer owns the screen says nothing: a newer
                // seed took over, and its screen is not this failure's to show.
                runOnUiThread {
                    if (links.ownsScreen(arrival)) showSeedFailure(e)
                }
                return@Thread
            }
            runOnUiThread {
                // A run that no longer owns the screen stays quiet: a newer
                // seed took over, and bringing the app up here would take the
                // screen away from the launch that is still running.
                if (!links.ownsScreen(arrival)) return@runOnUiThread
                // Bring the real app up. When the seed link arrived
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
        }.start()
    }

    /**
     * The failure, on the screen.
     *
     * The activity stays up with the reason instead of bringing the app up: the
     * flow waits for the app after every seed, so a seed that failed has to fail
     * that wait — at the seed — rather than later, on an assertion about a row
     * that was never written.
     *
     * The reason carries a fixed prefix, so the tools that report it can find
     * it by its text: the flow asserts that the prefix is not on the screen
     * (`Maestro/helpers/seed.yaml`), the logcat line starts with it, and the
     * runner greps the logcat for it. A plain view has no tag in the app's tag
     * registry — that registry is for composables — so the text is the handle.
     */
    private fun showSeedFailure(error: Throwable) {
        val view = android.widget.TextView(this)
        view.text = "${SeedFailure.PREFIX}${error.message ?: error.javaClass.simpleName}"
        view.setPadding(48, 48, 48, 48)
        setContentView(view)
    }

    /**
     * The profile the entities must land in, switched before anything is written.
     *
     * Split out of [seed] because it is the one part of seeding that is not a single
     * entity write: `switchTo` moves `activeProfileId`, which is what
     * `ProfileAwareCurrentUser.scopedUserId` resolves against, so it has to happen
     * before the first insert rather than alongside the others.
     *
     * The scoped identity is retargeted synchronously as well. The collector that
     * follows the active profile is asynchronous, so a write in the same breath as
     * the switch would pick up the *previous* profile's identity and put the rows
     * in the wrong place — a reset that wiped the wrong profile's content, or a
     * task that landed outside the profile it was seeded for.
     */
    private suspend fun switchToProfileIfRequested(
        params: Map<String, String>,
        profileRepo: ProfileRepository,
        currentUser: ProfileAwareCurrentUser,
    ) {
        if (!params.containsKey(SeedLinkKeys.PROFILE)) return
        val profileName = params[SeedLinkKeys.PROFILE]!!
        val existing = profileRepo.findByName(profileName)
        if (existing != null) {
            profileRepo.switchTo(existing.id)
            currentUser.switchToProfileNow(existing.id)
            return
        }
        val now = Clock.System.now()
        val newProfile =
            Profile(
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
        currentUser.switchToProfileNow(newProfile.id)
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
        val writer =
            SeedWriter(
                tasks = taskRepo,
                notes = notesRepo,
                projects = projectsRepo,
                currentUser = currentUser,
            )
        when {
            params.containsKey(SeedLinkKeys.TASK) -> {
                seedTask(params, writer)
            }

            params.containsKey(SeedLinkKeys.NOTE) -> {
                val spec =
                    NoteSeed(
                        title = params[SeedLinkKeys.NOTE]!!,
                        // `desc=` is the note's body here: a note with a title and
                        // no text is an empty screen, and the key already means
                        // "the text this entity holds".
                        body = params[SeedLinkKeys.DESC],
                    )
                val linkTo = params[SeedLinkKeys.LINK_TO]
                if (linkTo == null) {
                    writer.writeNote(spec)
                } else {
                    val result = writer.writeNoteLinkingToTask(spec, linkTo)
                    result.missingLinkTarget?.let { missing ->
                        android.util.Log.w(
                            "DebugSeedActivity",
                            "No task titled '$missing' to link to; seeding the note without the link",
                        )
                    }
                }
            }

            params.containsKey(SeedLinkKeys.PROJECT) -> {
                writer.writeProject(
                    ProjectSeed(
                        name = params[SeedLinkKeys.PROJECT]!!,
                        color = 0xFF2196F3.toInt(), // blue ARGB — deterministic default
                        stamp = Clock.System.now(),
                        // A flow that has to tap a project in the menu's tree asks
                        // for its id: a generated id is one a flow cannot write down.
                        id = params[SeedLinkKeys.ID]?.let { ProjectId.fromString(it) },
                        // A nested fixture names the parent by id: the child is
                        // seeded by a link that cannot know a generated id.
                        parentId = params[SeedLinkKeys.PARENT]?.let { ProjectId.fromString(it) },
                    ),
                )
                android.util.Log.i("DebugSeedActivity", "project '${params[SeedLinkKeys.PROJECT]}' was seeded")
            }

            // A profile with a hundred projects is a fixture — the menu's folded
            // section exists for it — and a hundred deep links are not a way to
            // make one. The names share a prefix, so `cleanup=Bulk` sweeps them.
            params.containsKey(SeedLinkKeys.PROJECTS) -> {
                val count = params[SeedLinkKeys.PROJECTS]!!.toIntOrNull() ?: 0
                val written =
                    writer.writeProjects(
                        count = count,
                        prefix = "Bulk",
                        stamp = Clock.System.now(),
                    )
                android.util.Log.i("DebugSeedActivity", "$count projects were seeded: ${written.size}")
            }

            // Profile creation and switch are handled by switchToProfileIfRequested.
            params.containsKey(SeedLinkKeys.PROFILE) -> {
                Unit
            }
        }
    }

    private suspend fun seedTask(
        params: Map<String, String>,
        writer: SeedWriter,
    ) {
        val baseTitle = params[SeedLinkKeys.TASK]!!
        val count =
            SeedCount.parse(params[SeedLinkKeys.COUNT])
                ?: run {
                    android.util.Log.e(
                        "DebugSeedActivity",
                        "Invalid count='${params[SeedLinkKeys.COUNT]}'; expected 1..${SeedCount.MAX}",
                    )
                    return
                }
        // A task stamped "now" is never stagnant: the health window is 21 days
        // without movement, and a flow cannot reach that bucket by seeding a
        // fresh task. `updatedAt=-22d` puts both timestamps in the past, which
        // is what the stagnation bucket is defined by.
        val stamp =
            params[SeedLinkKeys.UPDATED_AT]
                ?.let { resolveInstant(it) }
                ?: Clock.System.now()

        fun spec(index: Int): TaskSeed {
            val suffix = if (count == 1) "" else "-${index + 1}"
            val id =
                params[SeedLinkKeys.ID]?.let { baseId ->
                    if (count == 1) TaskId.fromString(baseId) else TaskId.fromString("$baseId-${index + 1}")
                }
            return TaskSeed(
                title = "$baseTitle$suffix",
                description = params[SeedLinkKeys.DESC],
                dueDate = params[SeedLinkKeys.DUE]?.let { resolveDate(it) },
                priority = resolvePriority(params[SeedLinkKeys.PRIORITY]),
                stamp = stamp,
                dependsOnTitle = params[SeedLinkKeys.BLOCKED_BY],
                projectName = params[SeedLinkKeys.IN_PROJECT],
                id = id,
            )
        }

        if (count == 1) {
            val result = writer.writeTask(spec(0))
            android.util.Log.i("DebugSeedActivity", "task '$baseTitle' was ${result.outcome}")
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
        } else {
            // One insert per row: replacing each row in turn reads the whole task
            // table twice per row, which is O(n²) for a thousand-row fixture.
            writer.createTasks((0 until count).map { spec(it) })
            android.util.Log.i("DebugSeedActivity", "seeded $count tasks with base title '$baseTitle'")
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

        // The profile is switched BEFORE anything is written or deleted.
        //
        // A reset reaches the profile that is active when it runs, so a link that
        // says `profile=X&reset=content` wipes X and nothing else: the demo
        // dataset can therefore reset a profile of its own instead of the
        // profile a person is using. The same is true of the seeding that
        // follows — the rows land in the profile the link named.
        switchToProfileIfRequested(params, profileRepo, currentUser)

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
        if (params.containsKey(SeedLinkKeys.RESET)) {
            resetContent()
            return
        }

        params[SeedLinkKeys.CLEANUP]?.let { prefixes ->
            cleanup(
                prefixes = prefixes.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                taskRepo = taskRepo,
                notesRepo = notesRepo,
                projectsRepo = projectsRepo,
            )
            return
        }

        // A session link changes who the app thinks it is, and nothing else. It
        // is the same shape as the others: a side effect the flow asks for
        // explicitly, so that the states the app really has — a local session
        // with no account, a signed-out session with the sign-in screen — are
        // reachable from a test, and not only the state the build starts in.
        if (params.containsKey(SeedLinkKeys.SESSION)) {
            switchSession(params[SeedLinkKeys.SESSION]!!)
            return
        }

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
                val sessionStore: SecureSessionManager by inject()
                TestAuthStubs
                    .installSignedInSession(sessionStore, this@DebugSeedActivity)
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

            else -> {
                android.util.Log.w(
                    "DebugSeedActivity",
                    "Unknown session state '$state'; expected 'anonymous', 'signedout' or 'signedin'",
                )
            }
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

    private fun parseParams(uri: Uri): Map<String, String> =
        uri.encodedQuery
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
            }?.toMap()
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
        val today =
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date

        return when (expr.lowercase()) {
            "today" -> {
                today
            }

            "tomorrow" -> {
                LocalDate.fromEpochDays(today.toEpochDays() + 1)
            }

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
            "now" -> {
                now
            }

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

    /**
     * Resolves a priority expression to a [TaskPriority].
     *
     * The spellings are the model's own names — `none`, `low`, `medium`, `high`,
     * `urgent` — read case-insensitively.
     *
     * A priority that is not one of them fails the seed: a task that was asked
     * for with a priority and got a different one is a fixture that looks right
     * and is not, and a screenshot of it is a screenshot of the wrong thing.
     */
    private fun resolvePriority(expr: String?): TaskPriority {
        if (expr == null) return TaskPriority.None
        return TaskPriority.entries.firstOrNull { it.name.equals(expr.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "Unknown priority '$expr'; expected one of ${TaskPriority.entries.joinToString { it.name }}",
            )
    }
}
