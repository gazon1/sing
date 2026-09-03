---
name: singularity-todo-secure-storage
description: Secure storage port pattern for KMP using expect/actual with libsecret shell-out on JVM, AES-GCM encrypted file fallback, and EncryptedSharedPreferences on Android. Use when building cross-platform secure credential storage.
---

# Singularity TODO — Secure Storage Port Pattern

This skill documents the secure storage architecture in the Singularity TODO KMP app: a `SecureStoragePort` interface implemented via `secret-tool` shell-out on Linux/JVM, AES-GCM encrypted file fallback, and `EncryptedSharedPreferences` on Android.

## Architecture

```
SecureStoragePort (interface — commonMain)
    │
    ├── JvmSecureStorage (jvmMain)
    │       ├── Linux: secret-tool CLI (libsecret)
    │       └── Any JVM: AES-GCM encrypted file fallback
    │
    ├── AndroidSecureStorage (androidMain)
    │       └── EncryptedSharedPreferences (security-crypto)
    │
    └── FakeSecureStorage (commonMain — tests)
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

All operations are suspending (they may do I/O). `isHardwareBacked()` returns `true` on Android (Keystore) and Linux with libsecret, `false` for the AES-GCM file fallback.

## JVM Implementation — JvmSecureStorage

**Linux path** (primary):
```kotlin
fun write(key: String, value: String) {
    val process = ProcessBuilder("secret-tool", "store",
        "--label=$key", "key=$key")
        .redirectErrorStream(true)
        .start()
    process.outputStream.bufferedWriter().use { it.write(value) }
    process.outputStream.close()
    val exit = process.waitFor()
    if (exit != 0) error("secret-tool exit $exit")
}
```

`secret-tool` stores in the user's GNOME keyring (or compatible D-Bus secret service). Requires `libsecret-1-dev` on the system.

**All JVM fallback** (when `secret-tool` is absent or fails):
- Encrypted file at `~/.config/singularity/secure.bin`
- Key derived via **PBKDF2WithHmacSHA256**: 100k iterations, 16-byte random salt stored in the file header
- AES-GCM (256-bit key) — authenticated encryption, no padding concerns

```kotlin
private fun deriveKey(salt: ByteArray): SecretKey {
    val spec = PBEKeySpec(
        System.getProperty("user.home")!!.toCharArray(), // user.home as salt source
        salt, ITERATIONS, KEY_BITS
    )
    return SecretKeySpec(factory.generateSecret(spec).encoded, "AES/GCM/NoPadding")
}
```

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

- `secret-tool` not found → fall back to AES-GCM file (graceful degradation)
- `secret-tool` fails with non-zero exit → throw `error("secret-tool exit $exit")`
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
| `shared/src/jvmMain/.../core/security/JvmSecureStorage.kt` | secret-tool + AES-GCM fallback |
| `shared/src/androidMain/.../core/security/AndroidSecureStorage.kt` | EncryptedSharedPreferences |
| `shared/src/commonMain/.../core/security/FakeSecureStorage.kt` | In-memory test double |
| `shared/src/commonMain/.../feature/settings/SettingsViewModel.kt` | Calls `secureStorage.write/delete` for AI keys |
