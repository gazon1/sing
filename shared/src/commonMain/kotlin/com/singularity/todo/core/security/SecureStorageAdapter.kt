package com.singularity.todo.core.security

import com.singularity.todo.core.auth.SecureStorage

/**
 * Presents the app's [SecureStoragePort] as the three methods
 * [com.singularity.todo.core.auth.SecureStorage] declares.
 *
 * An adapter rather than a widened interface, because the two have different jobs.
 * The port is the thing a platform implements and it answers a question only a
 * credential store can answer — whether the backing store is hardware-backed. The
 * narrow interface is what the config layer consumes, and asking it about keychain
 * strength would invite an answer it has no use for.
 *
 * Narrowing in one direction and not the other is deliberate: the config layer
 * depending on the *whole* port would mean a test of "which URL wins" also has to
 * stand up a keyring, which is a lot of machinery for a rule about precedence.
 */
class SecureStorageAdapter(private val port: SecureStoragePort) : SecureStorage {
    override suspend fun read(key: String): String? = port.read(key)

    override suspend fun write(key: String, value: String) = port.write(key, value)

    override suspend fun delete(key: String) = port.delete(key)
}
