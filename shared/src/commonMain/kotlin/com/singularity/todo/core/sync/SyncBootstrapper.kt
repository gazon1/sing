package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import kotlinx.coroutines.CancellationException

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
    private val tagGroupRepo: TagGroupRepository,
    private val timeTrackingRepo: TimeTrackingRepository,
    private val log: Logger = Logger.withTag("SyncBootstrapper"),
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
) {
    init {
        registerHandlers()
    }

    private fun registerHandlers() {
        engine.registerHandler(DocType.Task) { event ->
            handleEvent(event) { data: kotlinx.serialization.json.JsonObject ->
                val task = StableJson.decodeFromString(serializer<Task>(), data.toString())
                taskRepo.upsert(task)
            }
        }

        engine.registerHandler(DocType.Note) { event ->
            handleEvent(event) { data: kotlinx.serialization.json.JsonObject ->
                val note = StableJson.decodeFromString(serializer<Note>(), data.toString())
                noteRepo.upsert(note)
            }
        }

        engine.registerHandler(DocType.Project) { event ->
            handleEvent(event) { data: kotlinx.serialization.json.JsonObject ->
                val project = StableJson.decodeFromString(serializer<Project>(), data.toString())
                projectRepo.upsert(project)
            }
        }

        engine.registerHandler(DocType.Tag) { event ->
            handleEvent(event) { data: kotlinx.serialization.json.JsonObject ->
                val tag = StableJson.decodeFromString(serializer<Tag>(), data.toString())
                tagRepo.upsert(tag)
            }
        }

        engine.registerHandler(DocType.TagGroup) { event ->
            handleEvent(event) { data: kotlinx.serialization.json.JsonObject ->
                val tagGroup = StableJson.decodeFromString(serializer<TagGroup>(), data.toString())
                tagGroupRepo.upsert(tagGroup)
            }
        }

        // #177. The delete half of TimeEntry was registered long before the apply half,
        // and the gap is not cosmetic: without an apply handler an incoming time-entry
        // event is Unappliable, the cursor is held back, and every cycle re-receives the
        // same event and stalls identically. SeedPlanner enqueues these entities, so the
        // type is reachable from the push side too.
        engine.registerHandler(DocType.TimeEntry) { event ->
            handleEvent(event) { data: kotlinx.serialization.json.JsonObject ->
                val entry = StableJson.decodeFromString(serializer<TimeEntry>(), data.toString())
                timeTrackingRepo.upsert(entry)
            }
        }

        log.i { "Sync handlers registered for all DocTypes" }
    }

    /**
     * Shared handler logic for all entity types.
     * - CREATED / UPDATED: deserialize data and call [applyRemote]
     * - DELETED / RESTORED: call [deleteRemote] / [restoreRemote] with the entity ID
     *
     * Events with protocolVersion > [SyncProtocol.CURRENT_PROTOCOL_VERSION] are silently skipped.
     * This provides forward compatibility: a newer server never breaks an older client.
     *
     * Conflict detection is deferred to Tier 4 (HLC comparison).
     * Currently uses last-write-wins: remote always overwrites local.
     */
    private suspend fun handleEvent(
        event: SyncEvent,
        applyRemote: suspend (kotlinx.serialization.json.JsonObject) -> Unit,
    ): ApplyOutcome {
        if (event.protocolVersion > SyncProtocol.CURRENT_PROTOCOL_VERSION) {
            log.w {
                "Pull event [${event.entityId}][${event.eventType}][lsn=${event.serverLsn}]: protocol version ${event.protocolVersion} > ${SyncProtocol.CURRENT_PROTOCOL_VERSION}, skipping"
            }
            return ApplyOutcome.Applied
        }
        return try {
            when (event.eventType) {
                SyncEventType.CREATED,
                SyncEventType.UPDATED,
                SyncEventType.RESTORED,
                -> {
                    val data = event.data
                    if (data == null || data == kotlinx.serialization.json.JsonNull) {
                        log.w {
                            "Pull event [${event.entityId}][${event.eventType}][lsn=${event.serverLsn}]: no data, skipping"
                        }
                        return ApplyOutcome.Skipped("the event carries no document")
                    }
                    val obj = data as? kotlinx.serialization.json.JsonObject
                        ?: run {
                            log.w {
                                "Pull event [${event.entityId}][${event.eventType}][lsn=${event.serverLsn}]: data is not JsonObject, skipping"
                            }
                            return ApplyOutcome.Skipped("the event payload is not a document")
                        }
                    applyRemote(obj)
                    log.d { "Pull event [${event.eventType}][lsn=${event.serverLsn}]: applied" }
                    ApplyOutcome.Applied
                }

                SyncEventType.DELETED -> {
                    // Convert entityId String to the typed ID at the boundary, then ask
                    // the repository to delete.
                    //
                    // Every implementation dispatched to here makes that a *soft* delete —
                    // `TaskRepositoryImpl.delete` delegates to `softDelete`, and the rest
                    // call `softDeleteForUser` — which is what keeps the trash item on the
                    // receiving device instead of destroying it. That is a property of
                    // those repositories, not of this dispatch, and nothing here checks
                    // it: a new synced type whose `delete` is a genuine hard delete would
                    // silently remove the row — trash and all — on every other device,
                    // and nothing would say so. `SyncedEntityDeleteIsSoftTest` reads
                    // this dispatch table and checks each repository it names. See #195.
                    //
                    // A `RESTORED` event does not come here. It is handled above, in the
                    // same branch as `CREATED` and `UPDATED`, and applied as an ordinary
                    // upsert of a document whose delete marker is clear. That is a
                    // different operation from `SoftDeletable.restore`, and the two are
                    // kept consistent only by the server putting the right document in
                    // the event.
                    val outcome: Result<Unit> = when (event.entityType) {
                        DocType.Task -> taskRepo.delete(TaskId.fromString(event.entityId))

                        DocType.Note -> noteRepo.delete(NoteId.fromString(event.entityId))

                        DocType.Project -> projectRepo.delete(ProjectId.fromString(event.entityId))

                        DocType.Tag -> tagRepo.delete(TagId.fromString(event.entityId))

                        DocType.TagGroup -> tagGroupRepo.delete(TagGroupId.fromString(event.entityId))

                        DocType.TimeEntry -> timeTrackingRepo.delete(
                            TimeEntryId.fromString(event.entityId),
                        )
                    }
                    outcome.fold(
                        onSuccess = {
                            log.d { "Pull event [DELETED][lsn=${event.serverLsn}]: deleted" }
                            ApplyOutcome.Applied
                        },
                        onFailure = { e ->
                            // Reported as `Applied`, the server's delete is considered
                            // delivered while the row is still here, and the cursor moves
                            // past the only event that would ever remove it. The local and
                            // remote rows then differ with nothing left to say so.
                            log.e(e) {
                                "Pull event [${event.entityId}][DELETED]" +
                                    "[lsn=${event.serverLsn}]: delete failed"
                            }
                            ApplyOutcome.Failed(
                                "the delete did not happen: " +
                                    (e.message ?: e::class.simpleName.orEmpty()),
                            )
                        },
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            // The bytes on the wire will be identical on every later delivery, so
            // waiting cannot make this decode. It is the same class as the payload that
            // is not a document at all — one layer deeper — and it gets the same
            // outcome: the cursor moves past it and the event is counted as dropped.
            //
            // Holding the cursor here instead would wedge the account on one
            // undecodable row, forever, over a change that can never apply anyway.
            log.w {
                "Pull event [${event.entityId}][${event.eventType}][lsn=${event.serverLsn}]: " +
                    "payload does not decode, skipping"
            }
            ApplyOutcome.Skipped(
                "the event payload does not decode: " +
                    (e.message ?: e::class.simpleName.orEmpty()),
            )
        } catch (e: Throwable) {
            // Anything else that escapes the apply is a write that did not happen: a full
            // disk, a broken constraint, a corrupt store. That is [ApplyOutcome.Failed],
            // not [ApplyOutcome.Conflict] — and the difference is the whole bug.
            //
            // `Conflict` means "applied, but a field lost to a newer one", so the engine
            // advances the cursor past this event. Reported here, a storage failure was
            // therefore consumed: the server considered the change delivered, the row was
            // never written, and no later cycle would re-request it. The user's edit was
            // gone with nothing but a pull summary claiming the event had arrived.
            //
            // `Failed` keeps the cursor where it is, so the event is delivered again next
            // cycle — the same treatment the failed-delete arm above already gets, and the
            // one `ApplyOutcome`'s own KDoc promises for "an upsert that hit a storage
            // error".
            log.e(e) { "Pull event [${event.entityId}][${event.eventType}][lsn=${event.serverLsn}]: apply failed" }
            crashReporter.report(e, "sync.apply_failed")
            ApplyOutcome.Failed("the write did not happen: ${e.message ?: e::class.simpleName}")
        }
    }
}
