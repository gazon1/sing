package com.singularity.todo.core.seed

import com.singularity.todo.core.error.fold
import com.singularity.todo.feature.notes.NoteId
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
     * @throws IllegalArgumentException when the title is not a valid task title.
     */
    suspend fun writeTask(spec: TaskSeed): TaskSeedResult {
        val input = validatedInput(spec)

        val live = tasks.observeAll().first().filter { it.title == input.title && !it.isTrashed }
        val trashed = tasks.observeByFilter(TaskFilter.Trash).first().filter { it.title == input.title }
        val keeper = live.firstOrNull() ?: trashed.firstOrNull()

        val saved = if (keeper == null) {
            tasks.create(
                TaskDomain.buildTask(
                    input = input,
                    id = TaskId.generate(),
                    createdAt = spec.stamp,
                    updatedAt = spec.stamp,
                    userId = currentUser.current,
                ),
            ).getOrThrow()
        } else {
            // A row that a previous run trashed is the row the seed is about: it is
            // brought back rather than duplicated, so the fixture keeps one identity.
            if (keeper.isTrashed) {
                tasks.restore(keeper.id).getOrThrow()
            }
            tasks.update(
                keeper.copy(
                    title = input.title,
                    description = input.description,
                    priority = input.priority,
                    dueDate = input.dueDate,
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
            outcome = if (keeper == null) SeedOutcome.Created else SeedOutcome.Updated,
            missingDependency = applyDependency(spec, saved.id),
        )
    }

    /**
     * Makes the profile hold exactly one live note named [NoteSeed.title].
     *
     * The seed defines the title and nothing else, so a note that already carries
     * it is already what the seed asked for — it is left alone rather than written
     * again.
     */
    suspend fun writeNote(spec: NoteSeed): SeedResult<NoteId> {
        val existing = notes.observeAll().first()
            .firstOrNull { it.title == spec.title && !it.isDeleted && !it.isArchived }
        if (existing != null) {
            return SeedResult(id = existing.id, outcome = SeedOutcome.Updated)
        }
        val created = notes.createNoteWithTitle(spec.title).getOrThrow()
        return SeedResult(id = created, outcome = SeedOutcome.Created)
    }

    /**
     * Makes the profile hold exactly one live project named [ProjectSeed.name].
     *
     * An existing project with that name is updated — name, colour, description
     * and timestamps become what the seed says — and the other projects with that
     * name leave the live set.
     */
    suspend fun writeProject(spec: ProjectSeed): SeedResult<ProjectId> {
        val existing = projects.observeAll().first().filter { it.name == spec.name && !it.isDeleted }
        val keeper = existing.firstOrNull()
        if (keeper == null) {
            val created = projects.create(
                Project(
                    id = ProjectId.generate(),
                    name = spec.name,
                    color = spec.color,
                    description = spec.description,
                    createdAt = spec.stamp,
                    updatedAt = spec.stamp,
                    userId = currentUser.current,
                ),
            ).getOrThrow()
            return SeedResult(id = created.id, outcome = SeedOutcome.Created)
        }

        val updated = projects.update(
            keeper.copy(
                name = spec.name,
                color = spec.color,
                description = spec.description,
                createdAt = spec.stamp,
                updatedAt = spec.stamp,
            ),
        ).getOrThrow()
        existing.filter { it.id != updated.id }.forEach { projects.delete(it.id).getOrThrow() }
        return SeedResult(id = updated.id, outcome = SeedOutcome.Updated)
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
    private suspend fun applyDependency(spec: TaskSeed, taskId: TaskId): String? {
        val dependencyTitle = spec.dependsOnTitle ?: return null
        val dependency = tasks.observeAll().first()
            .firstOrNull { it.title == dependencyTitle && !it.isTrashed && it.id != taskId }
        if (dependency == null) return dependencyTitle
        tasks.setDependencies(taskId, setOf(dependency.id)).getOrThrow()
        return null
    }

    /** Validates the seed through the domain, so a seed cannot write an invalid title. */
    private fun validatedInput(spec: TaskSeed): CreateTaskInput =
        TaskDomain.createInput(
            title = spec.title,
            description = spec.description,
            priority = spec.priority,
            kind = TaskKind.Task,
            dueDate = spec.dueDate,
        ).fold(
            left = { error -> throw IllegalArgumentException("Invalid input: $error") },
            right = { it },
        )
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
 */
data class TaskSeed(
    val title: String,
    val description: String? = null,
    val dueDate: LocalDate? = null,
    val priority: TaskPriority = TaskPriority.None,
    val stamp: Instant,
    val dependsOnTitle: String? = null,
)

/** What a note seed defines. */
data class NoteSeed(val title: String)

/** What a project seed defines. */
data class ProjectSeed(val name: String, val color: Int, val description: String? = null, val stamp: Instant)

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
