package com.singularity.todo.core.security

/**
 * Platform-agnostic port for secure credential storage.
 * Abstracts platform-specific backends:
 *   - Android: EncryptedSharedPreferences (androidx.security)
 *   - JVM (desktop): the OS keyring binding, or an AES-GCM encrypted file fallback
 *
 * All operations are suspending to allow I/O without blocking.
 * The [isHardwareBacked] flag tells callers whether secrets live in an OS
 * credential store (Android Keystore, a desktop keyring) or a derived-file fallback.
 */
interface SecureStoragePort {
    /**
     * Reads a value by [key].
     * @return the stored value, or `null` if the key is absent.
     */
    suspend fun read(key: String): String?

    /**
     * Persists [value] under [key].
     * On platforms with a keyring (Android Keystore, a desktop OS keyring), the
     * value is stored in hardware-backed secure storage.
     */
    suspend fun write(
        key: String,
        value: String,
    )

    /**
     * Removes [key] and its associated value.
     * No-op if the key does not exist.
     */
    suspend fun delete(key: String)

    /**
     * Returns `true` when the platform offers an OS credential store to keep secrets in —
     * Android Keystore, or the keyring a desktop host provides — and `false` when only the
     * derived-key fallback file is available.
     *
     * It reports the backend, not where an individual secret went: nothing tracks the
     * per-write outcome, so a keyring that refuses a write leaves that secret in the
     * fallback file while this stays `true`.
     */
    fun isHardwareBacked(): Boolean
}
