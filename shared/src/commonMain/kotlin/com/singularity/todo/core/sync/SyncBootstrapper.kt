package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger

/**
 * Bootstraps the sync engine: registers pull handlers for all syncable entity types.
 *
 * Instantiated as a lazy singleton in [CoreDiModule] AFTER all feature repositories
 * are registered. The [SyncEngine] is already available (it's also a singleton in
 * [CoreDiModule]), so we just need the repositories to create the handlers.
 *
 * All event-handling logic (protocol version guard, JsonObject payload validation,
 * CREATED/UPDATED/RESTORED/DELETED dispatch) lives in [PullEventHandler]. This class
 * only registers the per-type handlers and delegates to [PullEventHandler] via
 * [SyncEngine.registerApplyingHandler].
 */
internal class SyncBootstrapper(
    /**
     * The sync engine. Nullable to allow a no-op placeholder to be passed during
     * [SyncEngine] construction — the real bootstrapper is created in a separate
     * Koin singleton, after the engine exists, via `get<SyncEngine>()`.
     * Subclasses must call [registerHandlers] once the engine is set.
     */
    private val engine: SyncEngine?,
    private val writer: SyncDocumentWriter,
    private val pullEventHandler: PullEventHandler,
    private val log: Logger = Logger.withTag("SyncBootstrapper"),
) {
    init {
        if (engine != null) registerHandlers()
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
            engine?.registerApplyingHandler(type, pullEventHandler) { data ->
                writer.upsert(type, data)
            }
        }

        log.i { "Sync handlers registered for all DocTypes" }
    }
}
