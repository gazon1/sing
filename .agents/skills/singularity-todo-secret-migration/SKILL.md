---
name: singularity-todo-secret-migration
description: One-shot migration of legacy DataStore-stored secrets (API keys, tokens) into the SecureStoragePort. Use when an old app version persisted a secret in DataStore and a new version moves it to the hardware-backed keychain — the migration runs on first DataStore access and is idempotent. Triggers on any task that introduces `SecureStoragePort` for a value previously kept in DataStore, or any `migrate / migration` keyword in DI wiring.
---

# Singularity TODO — Secret Migration

When the project moved the OpenAI API key from DataStore to `SecureStoragePort` (Android Keystore / Linux libsecret), existing users had a key in `DataStore[ai_api_key]` that the new code path ignored. This skill documents the migration pattern that preserved the value across the upgrade.

## The pattern

`shared/src/commonMain/kotlin/com/singularity/todo/feature/settings/AiApiKeyMigration.kt`:

```. Runkotlin
object AiApiKeyMigration {
    /** DataStore key used by the legacy app versions. */
    const val LEGACY_DATASTORE_KEY = "ai_api_key"

    /**
     * Idempotent — safe to call on every startup. Returns `true` if a key
     * was migrated in this call, `false` otherwise.
     */
    suspend fun run(
        dataStore: DataStore<Preferences>,
        secureStorage: SecureStoragePort,
    ): Boolean {
        // Don't overwrite an already-configured secure key.
        val existing = secureStorage.read(OpenAiConfig.KEY_OPENAI)
        if (!existing.isNullOrBlank()) return false

        val legacy = dataStore.data.first()[stringPreferencesKey(LEGACY_DATASTORE_KEY)]
            ?: return false

        secureStorage.write(OpenAiConfig.KEY_OPENAI, legacy)
        dataStore.edit { it.remove(stringPreferencesKey(LEGACY_DATASTORE_KEY)) }
        return true
    }
}
```

Three properties matter:

- **Idempotent** — no-op if the secure storage already has the value, or if there's no legacy key.
- **Doesn't overwrite** — preserves the user's *current* secure-storage value if any.
- **One-shot** — clears the DataStore entry on successful migration.

## Wire it into DI

`PlatformModule.android.kt` runs the migration lazily, on the first `get<DataStore>()` call:

```. Runkotlin
single<DataStore<Preferences>> {
    PreferenceDataStoreFactory.create { /* ... */ }
        .also { ds ->
            // One-shot migration: legacy versions stored the OpenAI key in
            // DataStore; newer versions only in SecureStorage. Runs at first
            // DataStore access, no-ops on subsequent launches.
            koinBridge { AiApiKeyMigration.run(ds, get<SecureStoragePort>()) }
        }
}
```

The `koinBridge { ... }` call is the project's standard suspend → sync DI bridge — see `singularity-todo-koin-suspend-bridge`. If you prefer to inline the migration into `MainActivity.onCreate` before `startKoin`, that's also valid; the DI approach is just one seam.

## Testing the migration

`AiApiKeyMigrationTest` in `shared/src/jvmTest/`:

```. Runkotlin
class AiApiKeyMigrationTest {

    @Test fun `migrates legacy key from DataStore to SecureStorage`() = runTest {
        val dataStore = newDataStore().apply { seedLegacy("sk-old") }
        val secure = FakeSecureStorage()

        val migrated = AiApiKeyMigration.run(dataStore, secure)

        assertTrue(migrated)
        assertEquals("sk-old", secure.read(OpenAiConfig.KEY_OPENAI))
        assertEquals(null, dataStore.data.first()[stringPreferencesKey(LEGACY_DATASTORE_KEY)])
    }

    @Test fun `does not overwrite an already-configured secure key`() = runTest {
        val dataStore = newDataStore().apply { seedLegacy("sk-old") }
        val secure = FakeSecureStorage(
            mutableMapOf(OpenAiConfig.KEY_OPENAI to "sk-existing"),
        )

        val migrated = AiApiKeyMigration.run(dataStore, secure)
        assertFalse(migrated)
        // Existing key preserved.
        assertEquals("sk-existing", secure.read(OpenAiConfig.KEY_OPENAI))
    }
}
```

Three test cases minimum: success, no-op when secure already has a key, no-op when DataStore has nothing. The third is `is no-op when no legacy key exists` (return false without writing).

### Test infrastructure: in-memory `DataStore`

The test file builds a tiny `DataStore<Preferences>` backed by `MutableStateFlow<Preferences>` — important: `flowOf(state)` does NOT work because reads would observe a stale snapshot. Use:

```. Runkotlin
private fun newDataStore(): DataStore<Preferences> {
    val state = MutableStateFlow<Preferences>(emptyPreferences())
    return object : DataStore<Preferences> {
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }
}
```

`AiApiKeyMigration.run` uses `dataStore.data.first()` — that reads from the StateFlow and sees writes performed via `updateData`.

## When to use this pattern

Use a `*Migration` object whenever:

- A user has data persisted by an older version that the new code ignores;
- The data is small and idempotent (a single value, a boolean, a string);
- The cost of running on every launch is negligible (one `Flow.first()` read, one `SecureStorage.read`, one `Map.containsKey` check).

Don't use it for:

- Large migrations (use a one-shot `WorkManager` job or versioned schema);
- Anything that can't be made idempotent without external state — see `singularity-todo-room-migration` for database migrations.

## Anti-patterns

- **Reading DataStore eagerly at `Application.onCreate`** — blocks startup. The lazy approach (`also { ds -> ... }`) defers cost until the first `get<DataStore>()`, which usually happens after splash.
- **Storing the migrated value back into DataStore "for caching"** — defeats the point of moving it to secure storage. If you need fast access, put a derived `Flow<String>` in `SecureStoragePort`'s impl (Android Keystore is fast enough — this was measured).
- **Skipping the "don't overwrite" check** — if the user has a fresh key from a recent reinstall, the migration will silently overwrite it.

## Related skills

- `singularity-todo-secure-storage` — the storage backend that secrets move *to*.
- `singularity-todo-koin-suspend-bridge` — the `koinBridge { ... }` helper used to call the migration from DI.
- `singularity-todo-ai-provider-settings` — the user-facing surface where the migrated key is consumed.