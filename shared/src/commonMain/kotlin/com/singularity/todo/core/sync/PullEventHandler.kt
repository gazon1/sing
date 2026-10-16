package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import kotlinx.serialization.SerializationException

/**
 * Handles a single pull event: classifies, deserializes, and applies or skips it.
 *
 * Extracted from [SyncBootstrapper.handleEvent] to make the event-handling logic
 * independently testable. One instance is created per [SyncBootstrapper]; it is
 * safe to share the same instance across entity types because [handleEvent]
 * is re-entrant and uses only its own parameters.
 *
 * ## Responsibilities
 *
 * - Protocol version guard (forward compatibility)
 * - Payload validation (null check, JsonObject cast)
 * - Dispatch to the provided [applyRemote] for CREATED / UPDATED / RESTORED
 * - [SyncDocumentWriter.delete] for DELETED
 * - [SerializationException] → [ApplyOutcome.Skipped] (cursor advances; same bytes
 *   on every delivery cannot be fixed by retry)
 * - Other throws → [ApplyOutcome.Failed] (cursor stays; retry may help)
 *
 * ## What stays in [SyncBootstrapper]
 *
 * - `writer.supportedTypes` iteration and handler registration
 * - The `engine?.registerHandler(type) { event -> handleEvent(event) { ... } }` closure
 */
internal class PullEventHandler(
    private val writer: SyncDocumentWriter,
    private val log: Logger = Logger.withTag("PullEventHandler"),
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    /**
     * Deletes a document during a DELETED pull event.
     *
     * Accepted as a suspend lambda rather than taken from [writer] directly so that
     * tests can exercise the DELETED branch without constructing six repository stubs.
     * Production callers pass `writer::delete`.
     */
    private val deleteDocument: suspend (DocType, String) -> Result<Unit> = writer::delete,
) {
    /**
     * Applies [event] or classifies why it could not be applied.
     *
     * @param event The incoming sync event.
     * @param applyRemote Called for CREATED / UPDATED / RESTORED with the validated
     *   JsonObject payload. The caller closes over the per-type `writer.upsert`.
     */
    suspend fun handleEvent(
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
        return runCatchingCancellable { apply(event, applyRemote) }
            .getOrElse { e -> classify(event, e) }
    }

    /**
     * Applies the event and reports what the apply itself decided.
     *
     * Everything a pull can do to a row happens here: the decode, the upsert and the
     * delete. It is a plain function returning an [ApplyOutcome], so it holds no
     * exception handling of its own — the classification lives in [classify], where
     * the width of what is caught can be read next to the reason for it.
     */
    private suspend fun apply(
        event: SyncEvent,
        applyRemote: suspend (kotlinx.serialization.json.JsonObject) -> Unit,
    ): ApplyOutcome =
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
                val outcome = deleteDocument(event.entityType, event.entityId)
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

    /**
     * Turns whatever escaped [apply] into the outcome the engine acts on.
     *
     * The width is deliberate and is the whole point of this function. The apply path
     * is a Room write, and what a write can throw has no common supertype in commonMain:
     * a full disk, a broken constraint and a corrupt store arrive as three different
     * driver-specific types, none of which is declared here. Naming two of them would
     * leave the third escaping past the engine and wedging the cursor, which is the
     * failure this code exists to prevent — so the boundary is one [Throwable] handler,
     * typed once, in one place, with the two cases below it that mean different things.
     *
     * Cancellation is not among them: [runCatchingCancellable] re-throws
     * `CancellationException` before it reaches here, and an [Error] propagates rather
     * than being reported as a retryable sync failure.
     */
    private fun classify(
        event: SyncEvent,
        e: Throwable,
    ): ApplyOutcome {
        if (e is SerializationException) {
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
            return ApplyOutcome.Skipped(
                "the event payload does not decode: " +
                    (e.message ?: e::class.simpleName.orEmpty()),
            )
        }
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
        return ApplyOutcome.Failed("the write did not happen: ${e.message ?: e::class.simpleName}")
    }
}
