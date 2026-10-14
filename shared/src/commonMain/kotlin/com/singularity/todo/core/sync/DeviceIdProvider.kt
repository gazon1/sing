package com.singularity.todo.core.sync

/**
 * The stable node identity a hybrid-logical-clock stamps its timestamps with.
 *
 * Extracted from [com.singularity.todo.core.auth.SessionStore] so that sync
 * depends on the one operation it actually needs — the device id — rather than
 * on the whole auth seam (tokens, email, clear). The id is not a secret: it
 * lives in plain DataStore by design, and [com.singularity.todo.core.auth.
 * DataStoreSessionStore] is the implementation the DI graph binds.
 */
interface DeviceIdProvider {

    /** Initializes and returns the device ID. Safe to call multiple times. */
    suspend fun getOrInitDeviceId(): String
}
