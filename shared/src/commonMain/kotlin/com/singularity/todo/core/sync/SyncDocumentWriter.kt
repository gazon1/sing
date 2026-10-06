package com.singularity.todo.core.sync

import com.singularity.todo.core.ids.TimeEntryId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tags.domain.model.TagGroup
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import com.singularity.todo.feature.tags.domain.port.TagGroupRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.timetracking.domain.TimeEntry
import com.singularity.todo.feature.timetracking.domain.port.TimeTrackingRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * Writes a sync document to the local store, by type.
 *
 * ## Why this is its own class
 *
 * "Put this document into the right table" was a lambda closed over six repositories,
 * built inside `SyncBootstrapper.registerHandlers` and reachable only from the pull
 * handlers. That was enough while the only document to write was one the server had
 * just sent.
 *
 * It stopped being enough for #203. Resolving a lost race means writing a document the
 * *server did not send* — the state the device recorded as confirmed — back over the
 * row the losing patch had changed. That is the same write, per type, by a caller that
 * is not a pull handler. Reaching into the handlers for it would mean driving the pull
 * path to perform a push-side correction, and the two have different failure
 * semantics: a pull that cannot apply holds the cursor back and is retried, while a
 * revert that cannot apply must not be retried into a loop.
 *
 * So the per-type knowledge moves here, and both callers share one statement of it.
 * That is also what keeps the six repositories a *single* list: adding a synced type
 * now means adding it in one place rather than in two that were allowed to disagree.
 *
 * ## The delete half is here for the same reason
 *
 * `SyncedEntityDeleteIsSoftTest` reads the dispatch table and asserts each named
 * repository deletes softly. Keeping [delete] beside [upsert] keeps that table whole:
 * splitting them would put the assertion's subject in one file and its guarantee in
 * another.
 */
internal class SyncDocumentWriter(
    private val taskRepo: TaskRepository,
    private val noteRepo: NotesRepository,
    private val projectRepo: ProjectsRepository,
    private val tagRepo: TagsRepository,
    private val tagGroupRepo: TagGroupRepository,
    private val timeTrackingRepo: TimeTrackingRepository,
) {

    /** The types this writer can store — the dispatch table, read as data. */
    val supportedTypes: Set<DocType> = setOf(
        DocType.Task,
        DocType.Note,
        DocType.Project,
        DocType.Tag,
        DocType.TagGroup,
        DocType.TimeEntry,
    )

    /**
     * Decodes [document] as [type] and upserts it.
     *
     * The document's own `user_id` and ids are used as they are. Nothing here reads
     * the signed-in account, because the caller decides which account a document
     * belongs to: on the pull path that is the scope the event was accepted for, and on
     * the lost-race path it is the row's own shadow. Overwriting either from the
     * session would be a second answer to a question the document already answers.
     */
    suspend fun upsert(type: DocType, document: JsonObject) {
        when (type) {
            DocType.Task ->
                taskRepo.upsert(StableJson.decodeFromString(serializer<Task>(), document.toString()))

            DocType.Note ->
                noteRepo.upsert(StableJson.decodeFromString(serializer<Note>(), document.toString()))

            DocType.Project ->
                projectRepo.upsert(StableJson.decodeFromString(serializer<Project>(), document.toString()))

            DocType.Tag ->
                tagRepo.upsert(StableJson.decodeFromString(serializer<Tag>(), document.toString()))

            DocType.TagGroup ->
                tagGroupRepo.upsert(StableJson.decodeFromString(serializer<TagGroup>(), document.toString()))

            DocType.TimeEntry ->
                timeTrackingRepo.upsert(
                    StableJson.decodeFromString(serializer<TimeEntry>(), document.toString()),
                )
        }
    }

    /**
     * Soft-deletes the row of [type] with [id].
     *
     * Every repository reached here makes this a soft delete — `TaskRepositoryImpl`
     * delegates to `softDelete`, the rest call `softDeleteForUser` — which is what
     * keeps the trash item on a receiving device. That is a property of those
     * repositories and not of this dispatch, and nothing here checks it: a new synced
     * type whose `delete` were a genuine hard delete would silently remove the row,
     * trash and all, on every other device. `SyncedEntityDeleteIsSoftTest` reads
     * [supportedTypes] and checks each repository it names. See #195.
     */
    suspend fun delete(type: DocType, id: String): Result<Unit> = when (type) {
        DocType.Task -> taskRepo.delete(TaskId.fromString(id))
        DocType.Note -> noteRepo.delete(NoteId.fromString(id))
        DocType.Project -> projectRepo.delete(ProjectId.fromString(id))
        DocType.Tag -> tagRepo.delete(TagId.fromString(id))
        DocType.TagGroup -> tagGroupRepo.delete(TagGroupId.fromString(id))
        DocType.TimeEntry -> timeTrackingRepo.delete(TimeEntryId.fromString(id))
    }
}
