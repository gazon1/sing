---
name: singularity-todo-secure-storage
description: Secure storage port pattern for KMP using expect/actual with the java-keyring OS keyring binding on JVM, an AES-GCM encrypted file fallback, and EncryptedSharedPreferences on Android. Use when building cross-platform secure credential storage.
---

# Singularity TODO — Secure Storage Port Pattern

This skill documents the secure storage architecture in the Singularity TODO KMP app: a `SecureStoragePort` interface implemented via the java-keyring OS keyring binding on the JVM, an AES-GCM encrypted file fallback, and `EncryptedSharedPreferences` on Android.

## Architecture

```
SecureStoragePort (interface — commonMain)
    │
    ├── JvmSecureStorage (jvmMain)
    │       ├── OS keyring via java-keyring (libsecret / macOS Keychain / Windows Credential Manager)
    │       └── AES-GCM encrypted file fallback when no keyring backend exists
    │
    ├── AndroidSecureStorage (androidMain)
    │       └── EncryptedSharedPreferences (security-crypto)
    │
    └── FakeSecureStorage (commonMain — test/fakes)
            └── In-memory MutableMap with Mutex
```

The interface is pure Kotlin with no platform-specific types. Both implementations are injected by platform-specific entry points.

## Interface

```kotlin
interface SecureStoragePort {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun delete(key: String)
    fun isHardwareBacked(): Boolean
}
```

All operations are suspending (they may do I/O). `isHardwareBacked()` returns `true` on Android (Keystore) and on a JVM host where a keyring backend exists — on any OS, not only Linux — and `false` on a JVM host served by the AES-GCM file fallback.

It reports whether a keyring backend *exists*, not where an individual secret went: nothing tracks the per-write outcome, so a keyring that refuses a write (locked, denied) leaves that secret in `secure.bin` while the flag still says `true`. It must not be loosened to `true` to make a badge look better (ADR 2026-10-27-jvm-secure-storage-fallback-is-not-a-secret-store).

## JVM Implementation — JvmSecureStorage

**OS keyring path** (primary):
```kotlin
private val keyring: Keyring? by lazy {
    try {
        com.github.javakeyring.Keyring.create()
    } catch (_: com.github.javakeyring.BackendNotSupportedException) {
        null
    }
}

protected open fun keyringAvailable(): Boolean = keyring != null
```

`com.github.javakeyring:java-keyring` talks to the platform's credential store: libsecret on
Linux, the Keychain on macOS, the Credential Manager on Windows. There is no `secret-tool`
binary and no subprocess. "A backend exists" is decided by whether `Keyring.create()`
succeeded — **never** by `System.getProperty("os.name")`. A platform gate is what once made a
macOS host with a keyring report itself fallback-backed.

**A write the keyring refuses** (locked or denied keyring):
- `keyringWrite` returns `false`, and `write()` falls through to the encrypted file.
- Nothing tracks that per-write outcome, so `isHardwareBacked()` can be `true` on a host where
  every secret sits in `secure.bin`.

**JVM fallback** (when no keyring backend exists, or the keyring refuses):
- Encrypted file at `~/.config/singularity/secure.bin`
- The file's AES key is a random 32-byte key kept in the OS keyring under
  `__singularity_fallback_aes_key__`, so a host with a keyring does not depend on
  anything derivable to protect the file
- On a host with no keyring at all, the key is derived from `user.home` with
  **PBKDF2WithHmacSHA256**, 100k iterations and the **fixed** salt `singularity-v1`.
  That is obfuscation, not protection — `user.home` is not a secret. The parameters are
  frozen: a different derivation re-derives a different key, every GCM tag fails, the
  file is erased as corrupt, and `backup.device_key_block` is lost
- AES-GCM (256-bit key) — authenticated encryption, no padding concerns

**Key derivation note**: Use `toCharArray()` not `toByteArray()` — `PBEKeySpec` expects `char[]`.

## Android Implementation — AndroidSecureStorage

Uses `EncryptedSharedPreferences` from `androidx.security:security-crypto`:

```kotlin
class AndroidSecureStorage(context: Context) : SecureStoragePort {
    private val prefs = EncryptedSharedPreferences.create(
        context, "secure_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override suspend fun read(key: String): String? = prefs.string[key, null]
    override suspend fun write(key: String, value: String) { prefs.string[key] = value }
    override suspend fun delete(key: String) { prefs.remove(key) }
    override fun isHardwareBacked() = true // Android Keystore
}
```

Backed by Android Keystore (hardware-backed when available, non-exportable key).

## Fake for Testing — FakeSecureStorage

```kotlin
class FakeSecureStorage(
    private val backing: MutableMap<String, String> = mutableMapOf(),
    private val hardwareBacked: Boolean = true
) : SecureStoragePort {
    private val mutex = Mutex()

    override suspend fun read(key: String): String? = mutex.withLock { backing[key] }
    override suspend fun write(key: String, value: String) = mutex.withLock { backing[key] = value }
    override suspend fun delete(key: String) = mutex.withLock { backing.remove(key); Unit }
    override fun isHardwareBacked(): Boolean = hardwareBacked
}
```

Thread-safe via `Mutex`. `delete` must return `Unit`, not `String?`.

## DI Wiring

All implementations are created in platform entry points and passed to `sharedModule(...)`:

```kotlin
// desktopApp/src/jvmMain/kotlin/.../main.kt
val secureStorage: SecureStoragePort = JvmSecureStorage()

// androidApp/src/androidMain/kotlin/.../MainActivity.kt
val secureStorage: SecureStoragePort = AndroidSecureStorage(applicationContext)

// shared/src/commonMain/.../core/di/AppModule.kt
sharedModule(database, settingsRepository, secureStorage, ...)
```

## Testing Pattern

```kotlin
@Test
fun `write and read round-trip`() = runTest {
    val storage = FakeSecureStorage()
    storage.write("ai_key_openai", "sk-test123")
    assertEquals("sk-test123", storage.read("ai_key_openai"))
}

@Test
fun `delete removes value`() = runTest {
    val storage = FakeSecureStorage()
    storage.write("key", "value")
    storage.delete("key")
    assertEquals(null, storage.read("key"))
}
```

No mocks — `FakeSecureStorage` is the test double.

## Error Handling

- No keyring backend (`BackendNotSupportedException`) → fall back to the AES-GCM file
- Keyring refuses a call (`PasswordAccessException`, locked or denied) → `read` reports the key absent, `write` falls back to the file, `delete` still clears the file
- AES-GCM decryption failure → `IOException` propagates (file corrupted or tampered)
- Android `EncryptedSharedPreferences` → delegates to Keystore, failures propagate

## When to Use This Pattern

- Storing API keys, access tokens, or other secrets in a KMP app
- Needing hardware-backed security on Android (Keystore) while supporting desktop JVM
- Wanting a pure Kotlin interface over platform-specific implementations
- Requiring testability without platform-specific mocking

## Key Files

| File | Purpose |
|---|---|
| `shared/src/commonMain/.../core/security/SecureStoragePort.kt` | Interface |
| `shared/src/jvmMain/.../core/security/JvmSecureStorage.kt` | java-keyring backend + AES-GCM fallback |
| `shared/src/androidMain/.../core/security/AndroidSecureStorage.kt` | EncryptedSharedPreferences |
| `shared/src/commonMain/.../test/fakes/FakeSecureStorage.kt` | In-memory test double |
| `shared/src/commonMain/.../feature/settings/SettingsViewModel.kt` | Calls `secureStorage.write/delete` for AI keys |
