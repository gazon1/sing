package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import kotlinx.serialization.SerializationException
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
    private val writer: SyncDocumentWriter,
    private val log: Logger = Logger.withTag("SyncBootstrapper"),
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
) {
    init {
        registerHandlers()
    }

    private fun registerHandlers() {
        // One registration per type, from the writer's own table. The per-type
        // knowledge moved to [SyncDocumentWriter] in #203 because a lost race has to
        // write a document the server never sent, and that is the same write per type
        // by a caller that is not a pull handler. Registering from [supportedTypes]
        // rather than listing the types here is what stops the two from disagreeing:
        // a type added to the writer and forgotten here would be writable and
        // unreachable, which is the shape the unwired-surface gate exists to catch.
        //
        // #177: the delete half of TimeEntry was registered long before the apply
        // half, and the gap was not cosmetic. Without an apply handler an incoming
        // time-entry event is Unappliable, the cursor is held back, and every cycle
        // re-receives the same event and stalls identically.
        writer.supportedTypes.forEach { type ->
            engine.registerHandler(type) { event ->
                handleEvent(event) { data: kotlinx.serialization.json.JsonObject ->
                    writer.upsert(type, data)
                }
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
                "Pull event [${event.entityId}][${event.eventType}][lsn=${event.serverLsn}]: " +
                    "protocol version ${event.protocolVersion} > ${SyncProtocol.CURRENT_PROTOCOL_VERSION}; " +
                    "event is not applied — a newer client wrote it and this device cannot decode it"
            }
            // Return Skipped (not Applied): the bytes will be identical on every later delivery,
            // so waiting cannot help, and a client that cannot decode the schema may not apply the
            // event correctly even if it has a newer protocol wire format. The cursor advances
            // so this device does not stall on one undecodable event, but the event is counted
            // as dropped so monitoring can see that it was skipped rather than processed.
            return ApplyOutcome.Skipped(
                "protocol version ${event.protocolVersion} is newer than ${SyncProtocol.CURRENT_PROTOCOL_VERSION}",
            )
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
                    // The typed-id conversion and the per-repository soft delete both
                    // live in [SyncDocumentWriter.delete]. What stays here is the
                    // classification of the outcome, because that is a property of the
                    // *event* and not of the table it names.
                    //
                    // A `RESTORED` event does not come here. It is handled above, in
                    // the same branch as `CREATED` and `UPDATED`, and applied as an
                    // ordinary upsert of a document whose delete marker is clear. That
                    // is a different operation from `SoftDeletable.restore`, and the two
                    // are kept consistent only by the server putting the right document
                    // in the event.
                    val outcome = writer.delete(event.entityType, event.entityId)
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
