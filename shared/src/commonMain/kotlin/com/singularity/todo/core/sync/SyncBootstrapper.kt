package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NotesRepository
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository

/**
 * Bootstraps the sync engine: registers pull handlers for all syncable entity types.
 *
 * Instantiated as a lazy singleton in [CoreDiModule] AFTER all feature repositories
 * are registered. The [SyncEngine] is already available (it's also a singleton in
 * [CoreDiModule]), so we just need the repositories to create the handlers.
 */
internal class SyncBootstrapper(
    private val engine: SyncEngine,
    private val taskRepo: TaskRepository,
    private val noteRepo: NotesRepository,
    private val projectRepo: ProjectsRepository,
    private val tagRepo: TagsRepository,
    private val log: Logger = Logger.withTag("SyncBootstrapper"),
) {
    init {
        registerHandlers()
    }

    private fun registerHandlers() {
        engine.registerHandler(DocType.Task) { event ->
            handleEvent(event) { data: kotlinx.serialization.json.JsonObject ->
                val task = StableJson.decodeFromString<Task>(data.toString())
                taskRepo.upsert(task)
            }
        }

        engine.registerHandler(DocType.Note) { event ->
            handleEvent(event) { data: kotlinx.serialization.json.JsonObject ->
                val note = StableJson.decodeFromString<Note>(data.toString())
                noteRepo.upsert(note)
            }
        }

        engine.registerHandler(DocType.Project) { event ->
            handleEvent(event) { data: kotlinx.serialization.json.JsonObject ->
                val project = StableJson.decodeFromString<Project>(data.toString())
                projectRepo.upsert(project)
            }
        }

        engine.registerHandler(DocType.Tag) { event ->
            handleEvent(event) { data: kotlinx.serialization.json.JsonObject ->
                val tag = StableJson.decodeFromString<Tag>(data.toString())
                tagRepo.upsert(tag)
            }
        }

        log.i { "Sync handlers registered for all DocTypes" }
    }

    /**
     * Shared handler logic for all entity types.
     * - CREATED / UPDATED: deserialize data and call [applyRemote]
     * - DELETED / RESTORED: call [deleteRemote] / [restoreRemote] with the entity ID
     *
     * Conflict detection is deferred to Tier 4 (HLC comparison).
     * Currently uses last-write-wins: remote always overwrites local.
     */
    private suspend fun handleEvent(
        event: SyncEvent,
        applyRemote: suspend (kotlinx.serialization.json.JsonObject) -> Unit,
    ): ApplyOutcome {
        return try {
            when (event.eventType) {
                SyncEventType.CREATED,
                SyncEventType.UPDATED,
                SyncEventType.RESTORED,
                -> {
                    val data = event.data
                    if (data == null || data == kotlinx.serialization.json.JsonNull) {
                        log.w { "Pull event [${event.entityId}][${event.eventType}][lsn=${event.serverLsn}]: no data, skipping" }
                        return ApplyOutcome.Applied
                    }
                    val obj = data as? kotlinx.serialization.json.JsonObject
                        ?: run {
                            log.w { "Pull event [${event.entityId}][${event.eventType}][lsn=${event.serverLsn}]: data is not JsonObject, skipping" }
                            return ApplyOutcome.Applied
                        }
                    applyRemote(obj)
                    log.d { "Pull event [${event.entityId}][${event.eventType}][lsn=${event.serverLsn}]: applied" }
                    ApplyOutcome.Applied
                }

                SyncEventType.DELETED -> {
                    // Soft-delete is not yet wired for remote delete — Tier 4.
                    log.d { "Pull event [${event.entityId}][DELETED][lsn=${event.serverLsn}]: soft-delete not implemented, skipping" }
                    ApplyOutcome.Applied
                }
            }
        } catch (e: Throwable) {
            log.e(e) { "Pull event [${event.entityId}][${event.eventType}][lsn=${event.serverLsn}]: apply failed" }
            ApplyOutcome.Conflict("Apply failed: ${e.message ?: e::class.simpleName}")
        }
    }
}
