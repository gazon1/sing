package com.singularity.todo.core.security

/**
 * Platform-agnostic port for secure credential storage.
 * Abstracts platform-specific backends:
 *   - Android: EncryptedSharedPreferences (androidx.security)
 *   - JVM/Linux: libsecret via `secret-tool` CLI, or AES-GCM encrypted file fallback
 *
 * All operations are suspending to allow I/O without blocking.
 * The [isHardwareBacked] flag tells callers whether secrets live on a hardware
 * keychain (e.g. Android Keystore, Linux libsecret) or a derived-file fallback.
 */
interface SecureStoragePort {

    /**
     * Reads a value by [key].
     * @return the stored value, or `null` if the key is absent.
     */
    suspend fun read(key: String): String?

    /**
     * Persists [value] under [key].
     * On platforms with a keyring (Android Keystore, Linux libsecret), the value
     * is stored in hardware-backed secure storage.
     */
    suspend fun write(key: String, value: String)

    /**
     * Removes [key] and its associated value.
     * No-op if the key does not exist.
     */
    suspend fun delete(key: String)

    /**
     * Returns `true` when secrets are stored in hardware-backed secure storage
     * (Android Keystore, Linux libsecret/GNOME Keyring), `false` when the
     * fallback encrypted-file backend is in use.
     */
    fun isHardwareBacked(): Boolean
}
