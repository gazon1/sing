package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tags.domain.port.TagGroupRepository
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.timetracking.domain.port.TimeTrackingRepository
import kotlinx.coroutines.flow.firstOrNull

/**
 * Uploads the data a device already held before the user signed in (REQ-OS-013).
 *
 * ## "Exactly once" is a claim about the second sign-in, not about the first
 *
 * The requirement has two halves and they pull in opposite directions. A second
 * sign-in on the same account must upload nothing further, and an interrupted seed
 * must resume rather than lose the rest. Both are satisfied by one ordering:
 * **mark the scope seeded only after every entity has been enqueued**, never
 * before.
 *
 * Marking first would make an interrupted seed look finished, and the documents
 * that never made it into the outbox would never be uploaded — silently, and
 * permanently, because the flag now says there is nothing to do. Marking last
 * costs nothing: re-running the planner re-enqueues what is already enqueued, and
 * the outbox coalesces per entity, so the second run replaces rather than
 * duplicates. `SeedPlannerTest` asserts both halves against a real outbox.
 *
 * ## Why there is no separate upload path
 *
 * The requirement says "through the same push path used for any other change", and
 * that is the part that makes the rest work. A second path would need its own
 * retry, its own backoff, its own dead-letter handling and its own shadow settling
 * — and it would be the path that is never exercised until a user with a year of
 * data signs in, which is the worst moment to discover it is broken. Seeding is
 * `SyncEngine.enqueue` called once per entity. Everything downstream is the code
 * that already runs all day.
 *
 * ## Why the repositories come in directly
 *
 * `core` does not depend on `feature` as a rule, and this class breaks it. So does
 * [SyncBootstrapper], and for the same reason: a bootstrapper's whole job is to be
 * the one place that knows every entity type exists. A registry of providers would
 * keep the dependency out and add six registration calls that have to be made in
 * the right order, to a class whose failure mode is "one type silently not
 * seeded".
 */
internal class SeedPlanner(
    private val stateRepository: SyncStateRepository,
    private val enqueue: suspend (SyncableEntity) -> Result<Unit>,
    private val taskRepo: TaskRepository,
    private val noteRepo: NotesRepository,
    private val projectRepo: ProjectsRepository,
    private val tagRepo: TagsRepository,
    private val tagGroupRepo: TagGroupRepository,
    private val timeTrackingRepo: TimeTrackingRepository,
    private val log: Logger = Logger.withTag("SeedPlanner"),
) {

    /**
     * Queues everything this scope already holds, once.
     *
     * Returns the number of entities enqueued; zero means the scope was already
     * seeded, which is the normal answer for every sign-in after the first.
     */
    suspend fun plan(scope: SyncScope): Result<Int> = runCatchingResult {
        if (stateRepository.isSeedCompleted(scope)) {
            log.d { "Scope $scope has already been seeded" }
            return@runCatchingResult 0
        }

        val entities = collect()
        var enqueued = 0
        var failed = 0
        entities.forEach { entity ->
            enqueue(entity)
                .onSuccess { enqueued++ }
                .onFailure { e ->
                    failed++
                    log.e(e) { "Could not queue ${entity.docType.key}/${entity.syncId} for seeding" }
                }
        }

        // The marker is written only when nothing was left behind. Marking a
        // partial run complete is how a document is stranded: the flag says there
        // is nothing to come back for, and the data is still only on the device.
        // The cost of getting this wrong in the other direction is a re-scan of
        // tables that are usually empty, plus enqueues that coalesce to the rows
        // already queued — cheap, and the alternative is permanent loss.
        val complete = failed == 0
        stateRepository.setSeedCompleted(scope, complete)
        log.i { "Seeded $enqueued of ${entities.size} documents for $scope (complete=$complete)" }
        enqueued
    }

    /**
     * Every entity the device already holds.
     *
     * `firstOrNull` rather than `first`: these are Flows scoped to a user, and a
     * scope that has just been signed into may not have produced a value yet. A
     * missing list is an empty list, and an empty list seeds nothing rather than
     * throwing — the next sync will find any data that appears later through the
     * ordinary edit path.
     */
    private suspend fun collect(): List<SyncableEntity> {
        val tasks: List<Task> = taskRepo.observeAll().firstOrNull().orEmpty()
        return buildList {
            addAll(tasks)
            addAll(noteRepo.observeAll().firstOrNull().orEmpty())
            addAll(projectRepo.observeAll().firstOrNull().orEmpty())
            addAll(tagRepo.observeAll().firstOrNull().orEmpty())
            addAll(tagGroupRepo.observeAll().firstOrNull().orEmpty())
            // Time entries are per task and there is no "all entries" observation,
            // so they are gathered through the tasks above. Entries whose task has
            // gone are not seeded, and that is the honest answer: an orphaned entry
            // belongs to a document the user can no longer see.
            tasks.forEach { task ->
                addAll(timeTrackingRepo.watchEntries(task.id).firstOrNull().orEmpty())
            }
        }
    }
}
