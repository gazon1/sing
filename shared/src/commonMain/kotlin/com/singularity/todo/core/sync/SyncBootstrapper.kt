package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.notes.NotesRepository
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tasks.domain.port.TaskRepository

/**
 * Bootstraps the sync engine: registers pull handlers for all syncable entity types.
 *
 * Instantiated as a lazy singleton in [CoreDiModule] AFTER all feature repositories
 * are registered. The [SyncEngine] is already available (it's also a singleton in
 * [CoreDiModule]), so we just need the repositories to create the handlers.
 *
 * Handlers are currently stubs — see [ADR sync-pull-application].
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
            // Stub — full implementation in Tier 4:
            // 1. Deserialize event.data → TaskEntity
            // 2. Call taskRepo.upsert(entity)
            // 3. Return ConflictOutcome if HlcConflictResolver says local is newer
            log.d { "Task pull event [${event.entityId}][${event.eventType}][lsn=${event.serverLsn}]: handler stub" }
            ApplyOutcome.Applied
        }

        engine.registerHandler(DocType.Note) { event ->
            log.d { "Note pull event [${event.entityId}][${event.eventType}][lsn=${event.serverLsn}]: handler stub" }
            ApplyOutcome.Applied
        }

        engine.registerHandler(DocType.Project) { event ->
            log.d { "Project pull event [${event.entityId}][${event.eventType}][lsn=${event.serverLsn}]: handler stub" }
            ApplyOutcome.Applied
        }

        engine.registerHandler(DocType.Tag) { event ->
            log.d { "Tag pull event [${event.entityId}][${event.eventType}][lsn=${event.serverLsn}]: handler stub" }
            ApplyOutcome.Applied
        }

        log.i { "Sync handlers registered for all DocTypes (stubs)" }
    }
}
