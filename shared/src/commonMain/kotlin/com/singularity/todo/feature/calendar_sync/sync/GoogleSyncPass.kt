package com.singularity.todo.feature.calendar_sync.sync

/**
 * One Google sync pass, as something a caller can fake.
 *
 * ## Why this exists
 *
 * `GoogleSyncCoordinator` holds the guard that matters most in this feature:
 *
 * ```kotlin
 * if (userId == UserId.anonymous) return Outcome.Declined("not signed in")
 * ```
 *
 * Every id-bearing store here is keyed by user, and `UserId.anonymous` is a value a
 * genuinely signed-out session holds. Without that check a signed-out app would sync
 * whatever credential sits under the anonymous profile's key — syncing one device's
 * calendar into another profile's tasks.
 *
 * That guard was untestable, because the coordinator took `engineProvider: () -> GoogleSyncEngine`
 * — a concrete class with four DAO collaborators and a network client behind it. There was
 * nothing to hand it but the real thing, so constructing one meant constructing a database.
 *
 * ## Why this is a five-line port and not a fake of the engine
 *
 * The alternative — mocking `GoogleSyncEngine` — would have meant either MockK on a final
 * class or a hand-written subclass overriding a `sync` that is not `open`. Either couples
 * the test to the engine's *shape* rather than its contract, and breaks the next time a
 * constructor parameter is added. An interface this narrow cannot drift: the coordinator only
 * ever needed "run one pass", so that is all it can be handed.
 *
 * The engine implements it, so production behaviour is unchanged and there is no second
 * implementation to keep in step.
 */
fun interface GoogleSyncPass {

    /**
     * Runs one pass against [calendarId], or throws.
     *
     * Throwing is fine and expected: [GoogleSyncCoordinator] converts it into
     * [GoogleSyncCoordinator.Outcome.Failed], which is a *different* answer from declining
     * to run. A fake that throws is how that path gets tested.
     */
    suspend fun sync(calendarId: String): GoogleSyncEngine.PassResult
}
