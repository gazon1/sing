package com.singularity.todo.core.security

/**
 * Platform-agnostic port for secure credential storage.
 * Abstracts platform-specific backends:
 *   - Android: EncryptedSharedPreferences (androidx.security), under the Android Keystore
 *   - JVM: the OS credential store via java-keyring, or an AES-GCM encrypted file fallback
 *
 * All operations are suspending to allow I/O without blocking.
 * The [usesPlatformKeyStore] flag tells callers which store holds the secrets:
 * the platform's own secure store, or the derived-file fallback.
 */
interface SecureStoragePort {
    /**
     * Reads a value by [key].
     * @return the stored value, or `null` if the key is absent.
     */
    suspend fun read(key: String): String?

    /**
     * Persists [value] under [key].
     * When the platform's own secure store holds the secret — the Android Keystore,
     * an OS credential store — the value is stored there and not in the fallback file.
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
     * Returns `true` when the platform's own secure store holds the secrets — the
     * Android Keystore, or an OS credential store (Secret Service, macOS Keychain,
     * Windows Credential Manager) on the desktop — and `false` when the derived-file
     * fallback is in use.
     *
     * The flag names the store, not the strength of the hardware behind it: a
     * credential store may itself be software-backed, so this is not a claim that
     * the secret sits in a secure element. What it does say is which store the
     * caller is dealing with — and `false` means the fallback, which is
     * obfuscation rather than protection; see the decision record
     * `2026-10-27-jvm-secure-storage-fallback-is-not-a-secret-store`.
     */
    fun usesPlatformKeyStore(): Boolean
}
