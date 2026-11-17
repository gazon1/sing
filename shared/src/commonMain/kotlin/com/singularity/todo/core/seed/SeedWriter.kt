package com.singularity.todo.core.seed

import com.singularity.todo.core.error.fold
import com.singularity.todo.core.links.InternalLinkSchemes
import com.singularity.todo.core.links.InternalLinks
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.domain.NoteContentMapper
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tasks.domain.TaskDomain
import com.singularity.todo.feature.tasks.domain.model.CreateTaskInput
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * Writes a seeded entity into the profile as a *definition* of the state the flow
 * expects, not as an append to whatever the profile happens to hold.
 *
 * ## Why the seed replaces
 *
 * A flow asserts on a row it seeded, and it addresses that row by *name*: the test
 * tag of a task row is derived from the title
 * ([com.singularity.todo.core.ui.TestTags.taskItem]). A seed that appends leaves the
 * flow with more than one candidate and no way to say which one it meant — the
 * "exactly one row" assumption every flow makes is false, and a flow that matches
 * the wrong row is a false pass.
 *
 * The old answer to that was a run id in the title: every run seeded
 * "r5869995730 PinnedTask2026", unique per run, so a leftover row could never be
 * mistaken for the current one. It bought isolation at the price of identity. The
 * row's name, its test tag and the failure message of every assertion against it
 * changed on every run, so a failure could not be read — *"is
 * "r5869995730 PinnedTask2026" visible?"* says nothing about what the task was
 * supposed to be called — and the measurement had to normalise the digits out of
 * the cause's name to have a stable key at all. The run id also leaked into the
 * app's own data: every seeded task was called "r5869995730 PinnedTask2026" in
 * the user's task list.
 *
 * Replacing is the stronger answer, not the weaker one. With the run id a leftover
 * row is a row the flow cannot see, and the flow's "exactly one row" assumption is
 * false; with a replacing seed there is exactly one row, whichever run wrote it.
 *
 * ## What "exactly one" means
 *
 * One row in the *live* set — what the task list, the search results and the
 * filters show. A row a previous run archived is out of that set; the app's delete
 * is a soft delete, so a duplicate that leaves the live set lands in the trash
 * rather than being destroyed. The seed never destroys a row it did not write.
 *
 * ## Where the identity comes from
 *
 * The title. A seed names the entity it defines, and the name is what the flow
 * asserts on, so the name is stable across runs. The deep link that carries it is
 * the entry point, not the logic: this class is the logic, and it is reachable
 * from a plain JVM test — which is the only way the rule "seeding twice leaves
 * one row" can be a fact rather than a hope.
 */
class SeedWriter(
    private val tasks: TaskRepository,
    private val notes: NotesRepository,
    private val projects: ProjectsRepository,
    private val currentUser: ProfileAwareCurrentUser,
) {
    /**
     * Makes the profile hold exactly one live task named [TaskSeed.title].
     *
     * An existing task with that title is *updated* — title, description, due date,
     * priority and timestamps become what the seed says — and the lifecycle flags
     * are cleared, because a task the flow seeded is a task the flow can see: a
     * task left completed, pinned or trashed by an earlier run is not the row the
     * flow asserts on. The row's id is kept, so the identity of the fixture
     * survives the re-seed.
     *
     * A task that does not exist yet is created.
     *
     * A seed that *names* an id ([TaskSeed.id]) owns it: the row the flow will
     * address by that id has to be the row the seed writes, so a row of the same
     * title under a different id is not that row — it leaves the live set and the
     * named one takes its place.
     *
     * @throws IllegalArgumentException when the title is not a valid task title, or
     *   when the seed names a project that is not there.
     */
    suspend fun writeTask(spec: TaskSeed): TaskSeedResult {
        // The project is resolved before the row is written: a task that was
        // supposed to sit in a project and does not is a fixture that lies about
        // what it holds, so the seed fails instead of writing the task anyway.
        val projectId = spec.projectName?.let { resolveProjectId(it) }
        val input = validatedInput(spec, projectId)

        val live = tasks.observeAll().first().filter { it.title == input.title && !it.isTrashed }
        val trashed = tasks.observeByFilter(TaskFilter.Trash).first().filter { it.title == input.title }
        val keeper = live.firstOrNull() ?: trashed.firstOrNull()

        // The keeper is reusable only when the seed does not name an id, or when the
        // row already carries the named id. A row of the same title under a
        // different id is a different row — a flow that addresses the seed by the
        // named id would be looking at the wrong one.
        val keeperUsable = keeper != null && (spec.id == null || keeper.id == spec.id)

        val saved =
            if (keeper == null || !keeperUsable) {
                val created =
                    tasks
                        .create(
                            TaskDomain.buildTask(
                                input = input,
                                id = spec.id ?: TaskId.generate(),
                                createdAt = spec.stamp,
                                updatedAt = spec.stamp,
                                userId = currentUser.current,
                            ),
                        ).getOrThrow()
                // The row the seed replaces leaves the live set: two live rows with one
                // name is a state no flow can assert on. A trashed row is already out
                // of it and is left alone.
                keeper?.takeIf { !it.isTrashed }?.let { tasks.softDelete(it.id).getOrThrow() }
                created
            } else {
                // A row that a previous run trashed is the row the seed is about: it is
                // brought back rather than duplicated, so the fixture keeps one identity.
                if (keeper.isTrashed) {
                    tasks.restore(keeper.id).getOrThrow()
                }
                tasks
                    .update(
                        keeper.copy(
                            title = input.title,
                            description = input.description,
                            priority = input.priority,
                            dueDate = input.dueDate,
                            // A seed that names no project leaves the project the row
                            // already has; a seed that names one moves the task there.
                            projectId = projectId ?: keeper.projectId,
                            completedAt = null,
                            archivedAt = null,
                            isPinned = false,
                            createdAt = spec.stamp,
                            updatedAt = spec.stamp,
                        ),
                    ).getOrThrow()
            }

        // The other rows with this name leave the live set: two live rows with one
        // name is a state no flow can assert on.
        live.filter { it.id != saved.id }.forEach { tasks.softDelete(it.id).getOrThrow() }

        return TaskSeedResult(
            id = saved.id,
            outcome = if (keeper == null || !keeperUsable) SeedOutcome.Created else SeedOutcome.Updated,
            missingDependency = applyDependency(spec, saved.id),
        )
    }

    /**
     * Creates every task in [specs] without the per-title dedupe [writeTask] does.
     *
     * A bulk fixture's titles are distinct and the runner wipes the profile before
     * the flow, so there is nothing to dedupe against. [writeTask] reads the whole
     * task table twice per row, which is O(n²) for a few thousand titles; this
     * builds the rows and creates them in one transaction. Dependencies are not
     * applied.
     *
     * A spec that names a project gets it, exactly as in [writeTask]: the project
     * is resolved *before* any row is written, so a name that is not there fails
     * the whole bulk and leaves nothing behind, and ten tasks in one project are
     * one lookup rather than ten.
     *
     * @throws IllegalArgumentException when a spec names a project that is not there.
     */
    suspend fun createTasks(specs: List<TaskSeed>): List<TaskId> {
        // One lookup per distinct project name: the same project named by ten
        // specs is one project, and asking the repository ten times is ten reads
        // for an answer it already gave.
        val projectIds =
            specs
                .mapNotNull { it.projectName }
                .distinct()
                .associateWith { name -> resolveProjectId(name) }
        return tasks
            .createAll(
                specs.map { spec ->
                    TaskDomain.buildTask(
                        input = validatedInput(spec, spec.projectName?.let { projectIds.getValue(it) }),
                        id = spec.id ?: TaskId.generate(),
                        createdAt = spec.stamp,
                        updatedAt = spec.stamp,
                        userId = currentUser.current,
                    )
                },
            ).getOrThrow()
            .map { it.id }
    }

    /**
     * Makes the profile hold exactly one live note named [NoteSeed.title].
     *
     * The seed defines the title and nothing else, so a note that already carries
     * it is already what the seed asked for — it is left alone rather than written
     * again. A seed that names a [NoteSeed.body] writes that body too: the body is
     * what the note reads like, and a note with a title and no text is an empty
     * screen.
     */
    suspend fun writeNote(spec: NoteSeed): SeedResult<NoteId> {
        val existing =
            notes
                .observeAll()
                .first()
                .firstOrNull { it.title == spec.title && !it.isDeleted && !it.isArchived }
        val created = existing == null
        val id = existing?.id ?: notes.createNoteWithTitle(spec.title).getOrThrow()
        spec.body?.let { body ->
            notes.updateContent(id, spec.title, body, NoteContentMapper.toHtml(body)).getOrThrow()
        }
        return SeedResult(id = id, outcome = if (created) SeedOutcome.Created else SeedOutcome.Updated)
    }

    /**
     * Makes the profile hold exactly one live note named [NoteSeed.title] whose body
     * links to the task named [taskTitle].
     *
     * The link is written the way the app itself writes one: an anchor whose href is
     * the shared grammar's URL (`task://<id>`) and whose label is the target's title.
     * The seed does not invent a format of its own — the note it produces is one the
     * editor could have produced, and it is the note the link machinery reads.
     *
     * The outgoing links are written too, so a note that links to a task is a note the
     * backlink query finds. A link that exists only in the body would be a link the
     * app cannot answer a question about.
     *
     * A seed that names a body writes that body first and the link after it: a note
     * that points at a task reads as a note about something, not as a bare link.
     *
     * **A missing target does not cost the text.** A link target that is not there is
     * reported, but the body is written anyway: a note whose text was thrown away
     * because the task it pointed at was missing is a note the flow asked for and did
     * not get. The outgoing links are emptied in that case, so a re-seed does not
     * leave the previous run's link behind.
     *
     * @param taskTitle the title of the task the note links to. It is named rather
     *   than addressed by id because a caller that seeds the task first cannot know
     *   the id the app gave it.
     * @return the id of the note, whether it was created or replaced, and the title of
     *   the link target when there was no such task.
     */
    suspend fun writeNoteLinkingToTask(
        spec: NoteSeed,
        taskTitle: String,
    ): NoteSeedResult {
        val target =
            tasks
                .observeAll()
                .first()
                .firstOrNull { it.title == taskTitle && !it.isTrashed }
        val existing =
            notes
                .observeAll()
                .first()
                .firstOrNull { it.title == spec.title && !it.isDeleted && !it.isArchived }
        val noteId = existing?.id ?: notes.createNoteWithTitle(spec.title).getOrThrow()
        val outcome = if (existing == null) SeedOutcome.Created else SeedOutcome.Updated

        val url = target?.let { InternalLinks.urlFor(InternalLinkSchemes.TASK, it.id.value) }
        val targetTitle = target?.title
        // The seed's own text comes first and the link follows it: a note that
        // points at a task reads as a note about something, not as a bare link.
        val markdown =
            when {
                url == null || targetTitle == null -> spec.body
                spec.body == null -> linkMarkdown(targetTitle, url)
                else -> "${spec.body}\n\n${linkMarkdown(targetTitle, url)}"
            }
        // A seed that names no body writes no body: the note keeps the text it
        // already has. A seed that names one writes it, link or no link — the text
        // is what the caller asked for, and a missing link target is not a reason
        // to drop it.
        if (markdown != null) {
            notes.updateContent(noteId, spec.title, markdown, NoteContentMapper.toHtml(markdown)).getOrThrow()
        }
        // The links are the note's own: a re-seed with no target empties them
        // rather than leaving the previous run's link in place.
        notes.setOutgoingLinks(noteId, listOfNotNull(url)).getOrThrow()
        return NoteSeedResult(id = noteId, outcome = outcome, missingLinkTarget = if (url == null) taskTitle else null)
    }

    /**
     * The markdown of a link to the task named [title] at [url].
     *
     * The label is the target's title and the href is the shared grammar's URL,
     * which is what the app's link machinery reads — the seed writes the link the
     * editor would have written, not one of its own shape.
     */
    private fun linkMarkdown(
        title: String,
        url: String,
    ): String = "[$title]($url)"

    /**
     * Makes the profile hold exactly one live project for this seed.
     *
     * A seed that names an id owns that row: the row is addressed by its id and
     * the name the seed carries is the copy the row ends up with — so a seed that
     * names an id that is already there renames it. A seed that names only a name
     * owns the row with that name; an existing project with that name is updated
     * — name, colour, description and timestamps become what the seed says — and
     * the other projects with that name leave the live set.
     *
     * A seed that names a [ProjectSeed.parentId] puts the project below that
     * parent; a seed that does not leaves the parent the row already has.
     *
     * A parent that is not there is a broken fixture, and a broken fixture
     * that hangs below nothing becomes a root — the one row the Android menu
     * must not show. So the seed fails instead: nothing is written, the
     * hierarchy stays as it was, and the caller is told at once.
     *
     * @throws IllegalArgumentException when the named parent is not there, is
     *   the project itself, or is one of its descendants — a cycle would make
     *   the hierarchy unreadable.
     */
    suspend fun writeProject(spec: ProjectSeed): SeedResult<ProjectId> {
        val live = projects.observeAll().first().filter { !it.isDeleted }
        val byId = spec.id?.let { id -> live.firstOrNull { it.id == id } }
        val byName = live.filter { it.name == spec.name }
        val keeper = byId ?: byName.firstOrNull()
        // A named parent that is there wins; a seed that names none keeps the
        // parent the row already has.
        // The project answers to two ids while the seed runs: the id the seed
        // names, and the id the row already has. A name-matched row is
        // rewritten to the seeded id, and the children still point at the old
        // one — so both ids are the project, and a cycle through either of
        // them is a cycle.
        val ownIds = setOfNotNull(spec.id, keeper?.id)
        val parentId =
            spec.parentId?.let { parent ->
                resolveParent(parent, ownIds = ownIds, name = spec.name, live = live)
            } ?: keeper?.parentId
        if (keeper == null) {
            val created =
                projects
                    .create(
                        Project(
                            id = spec.id ?: ProjectId.generate(),
                            name = spec.name,
                            color = spec.color,
                            description = spec.description,
                            createdAt = spec.stamp,
                            updatedAt = spec.stamp,
                            userId = currentUser.current,
                            parentId = parentId,
                        ),
                    ).getOrThrow()
            return SeedResult(id = created.id, outcome = SeedOutcome.Created)
        }

        val updated =
            projects
                .update(
                    keeper.copy(
                        id = spec.id ?: keeper.id,
                        name = spec.name,
                        color = spec.color,
                        description = spec.description,
                        createdAt = spec.stamp,
                        updatedAt = spec.stamp,
                        // A seed that names a parent moves the project under it;
                        // a seed that does not leaves the parent alone.
                        parentId = parentId,
                    ),
                ).getOrThrow()
        // A seed that named only a name owns the *name*: the other rows carrying
        // it leave the live set. A seed that named an id owns the *row*, and the
        // rows that happen to share its new name are none of its business.
        if (byId == null) {
            byName.filter { it.id != updated.id }.forEach { projects.delete(it.id).getOrThrow() }
        }
        return SeedResult(id = updated.id, outcome = SeedOutcome.Updated)
    }

    /**
     * The parent a seed named, or a failure.
     *
     * The parent has to be there: a project that hangs below nothing is a root,
     * and a root is the one row the Android menu lists — a fixture that failed
     * this way would put the wrong row in the menu, and the flow would assert on
     * a project it never seeded.
     *
     * The parent must not be the project itself, and must not be one of its
     * descendants: the hierarchy is walked by parent, so a cycle makes it
     * unreadable. The app's own parent picker offers root projects only, so a
     * cycle can only come from a seed — which is why the seed is where it is
     * refused.
     *
     * The project is known under two ids while the seed runs — the id the seed
     * names and the id the row already has — and both count as the project: a
     * cycle through either one is a cycle, and a row that is rewritten to the
     * seeded id leaves its children pointing at the old one.
     */
    private fun resolveParent(
        parent: ProjectId,
        ownIds: Set<ProjectId>,
        name: String,
        live: List<Project>,
    ): ProjectId {
        require(parent !in ownIds) { "'$name' cannot be its own parent" }
        val named = live.firstOrNull { it.id == parent }
        require(named != null) { "no project with id '${parent.value}' to hang '$name' below" }
        var ancestor = named.parentId
        var guard = 0
        while (ancestor != null && guard++ <= live.size) {
            require(ancestor !in ownIds) {
                "hanging '$name' below '${named.name}' would make a cycle"
            }
            ancestor = live.firstOrNull { it.id == ancestor }?.parentId
        }
        return parent
    }

    /**
     * Writes [count] projects named after their number.
     *
     * A profile with a hundred projects is the fixture the menu's folded
     * section exists for, and a hundred deep links are not a fixture — they are
     * a hundred launches. The ids are the numbers, so a flow can address one
     * of them; the names share [prefix], so one `cleanup=` sweep removes them
     * again.
     *
     * @param prefix the prefix of the names — the handle a cleanup sweeps by.
     * @return the ids of the projects, in the order they were written.
     */
    suspend fun writeProjects(
        count: Int,
        prefix: String,
        stamp: Instant,
        color: Int = 0xFF2196F3.toInt(),
    ): List<ProjectId> =
        (1..count).map { index ->
            val number = index.toString().padStart(3, '0')
            projects
                .create(
                    Project(
                        id = ProjectId.fromString("$BULK_ID_PREFIX-$number"),
                        name = "$prefix $number",
                        color = color,
                        createdAt = stamp,
                        updatedAt = stamp,
                        userId = currentUser.current,
                    ),
                ).getOrThrow()
                .id
        }

    private companion object {
        /** The prefix of the ids of the bulk-written projects. */
        const val BULK_ID_PREFIX = "bulk"
    }

    /**
     * Points the seeded task at the task named [TaskSeed.dependsOnTitle].
     *
     * The dependency is named by title because a deep link cannot know an id it has
     * not created itself; the dependency has to be seeded first.
     *
     * @return the title of the dependency that was not found, or null when the
     *   dependency was set.
     */
    private suspend fun applyDependency(
        spec: TaskSeed,
        taskId: TaskId,
    ): String? {
        val dependencyTitle = spec.dependsOnTitle ?: return null
        val dependency =
            tasks
                .observeAll()
                .first()
                .firstOrNull { it.title == dependencyTitle && !it.isTrashed && it.id != taskId }
        if (dependency == null) return dependencyTitle
        tasks.setDependencies(taskId, setOf(dependency.id)).getOrThrow()
        return null
    }

    /** Validates the seed through the domain, so a seed cannot write an invalid title. */
    private fun validatedInput(
        spec: TaskSeed,
        projectId: ProjectId? = null,
    ): CreateTaskInput =
        TaskDomain
            .createInput(
                title = spec.title,
                description = spec.description,
                priority = spec.priority,
                kind = TaskKind.Task,
                projectId = projectId,
                dueDate = spec.dueDate,
            ).fold(
                left = { error -> throw IllegalArgumentException("Invalid input: $error") },
                right = { it },
            )

    /**
     * The id of the live project named [name].
     *
     * A project that is not there is a broken fixture, and a task that was
     * supposed to sit in it and does not is a task the flow would assert on as
     * being in a project it is not in. So the seed fails, the same way a project
     * seed with a parent that is not there fails.
     *
     * @throws IllegalArgumentException when no live project carries that name.
     */
    private suspend fun resolveProjectId(name: String): ProjectId {
        val project =
            projects
                .observeAll()
                .first()
                .firstOrNull { it.name == name && !it.isDeleted }
        return project?.id
            ?: throw IllegalArgumentException("No project named '$name' to put the task in")
    }
}

/**
 * What a task seed defines.
 *
 * @param stamp the instant the seeded row is stamped with — both its created and
 *   its updated timestamp. A task stamped in the past is what the health buckets
 *   are defined by, so the stamp is part of the definition and not an artefact of
 *   when the seed ran.
 * @param dependsOnTitle the title of the task this one depends on. A dependency on
 *   an incomplete task is what makes a task *blocked*.
 * @param projectName the name of the project the task belongs to. The name, not
 *   the id: a link that seeds the task cannot know the id the app gave the
 *   project. A project that is not there fails the seed — a task in no project
 *   is not the task the fixture asked for.
 * @param id the id the seeded row carries. A seed that names an id owns it: the row
 *   the flow addresses by that id has to be the row the seed writes. It is what
 *   makes a link to the task addressable — a deep link names an id, and a flow
 *   cannot know the id the app generated.
 */
data class TaskSeed(
    val title: String,
    val description: String? = null,
    val dueDate: LocalDate? = null,
    val priority: TaskPriority = TaskPriority.None,
    val stamp: Instant,
    val dependsOnTitle: String? = null,
    val projectName: String? = null,
    val id: TaskId? = null,
)

/**
 * What a note seed defines.
 *
 * @param body the note's text. A note with a title and no body is an empty
 *   screen, so a seed that has something to say names it.
 */
data class NoteSeed(val title: String, val body: String? = null)

/**
 * What a project seed defines.
 *
 * @param id The project's id, when the flow needs to address it. A project's
 *   id is generated otherwise, and a generated id is one a flow cannot write
 *   down — so a flow that has to tap a project in the tree asks for one by
 *   name.
 * @param parentId The id of the project this one hangs below, when the flow
 *   needs a nested fixture. The parent is named by id because the child is
 *   seeded by a link that cannot know a generated id. A parent that is not
 *   there fails the seed: the project is not written at all, because a project
 *   that hangs below nothing is a root — the one row the Android menu lists.
 */
data class ProjectSeed(
    val name: String,
    val color: Int,
    val description: String? = null,
    val stamp: Instant,
    val id: ProjectId? = null,
    val parentId: ProjectId? = null,
)

/**
 * Whether a seed wrote a new row or replaced the row that was already there.
 *
 * The distinction is the point of the whole class: a seed that reports [Created]
 * twice has appended, and a flow cannot tell which of the two rows it meant.
 */
enum class SeedOutcome {
    Created,
    Updated,
}

/**
 * The result of writing a seeded entity.
 *
 * @param id the id of the row the profile now holds.
 * @param outcome whether the row was created or replaced.
 */
data class SeedResult<out Id>(val id: Id, val outcome: SeedOutcome)

/**
 * The result of writing a seeded task.
 *
 * @param missingDependency the title of a dependency that could not be found. The
 *   task was written without it, so the flow that asked for a *blocked* task has
 *   to be told rather than left to time out on an assertion.
 */
data class TaskSeedResult(val id: TaskId, val outcome: SeedOutcome, val missingDependency: String? = null)

/**
 * The result of writing a seeded note.
 *
 * @param missingLinkTarget the title of the link target that could not be found. The
 *   note was written without the link, so the flow that asked for a linked note has
 *   to be told rather than left to time out on an assertion.
 */
data class NoteSeedResult(val id: NoteId, val outcome: SeedOutcome, val missingLinkTarget: String? = null)
