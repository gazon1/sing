@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.llm.OpenAiConfig
import com.singularity.todo.core.security.FakeSecureStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("fast")
class AiApiKeyMigrationTest {

    @Test fun migratesLegacyKeyFromDataStoreToSecureStorage() = runTest {
        val dataStore = newDataStore().apply { seedLegacy("sk-old") }
        val secure = FakeSecureStorage()

        val migrated = AiApiKeyMigration.run(dataStore, secure)

        assertTrue(migrated)
        assertEquals("sk-old", secure.read(OpenAiConfig.KEY_OPENAI))
        // DataStore key cleared
        assertEquals(null, dataStore.data.first()[stringPreferencesKey(AiApiKeyMigration.LEGACY_DATASTORE_KEY)])
    }

    @Test fun isNoopWhenNoLegacyKeyExists() = runTest {
        val dataStore = newDataStore()
        val secure = FakeSecureStorage()

        val migrated = AiApiKeyMigration.run(dataStore, secure)

        assertFalse(migrated)
        assertEquals(null, secure.read(OpenAiConfig.KEY_OPENAI))
    }

    @Test fun doesNotOverwriteAlreadyConfiguredSecureKey() = runTest {
        val dataStore = newDataStore().apply { seedLegacy("sk-old") }
        val secure = FakeSecureStorage(
            mutableMapOf(OpenAiConfig.KEY_OPENAI to "sk-existing"),
        )

        val migrated = AiApiKeyMigration.run(dataStore, secure)

        assertFalse(migrated)
        // Existing key preserved
        assertEquals("sk-existing", secure.read(OpenAiConfig.KEY_OPENAI))
        // Legacy key left in place (caller can clean up later if desired)
        assertEquals("sk-old", dataStore.data.first()[stringPreferencesKey(AiApiKeyMigration.LEGACY_DATASTORE_KEY)])
    }
}

// ─── Test infrastructure ──────────────────────────────────────────────────────────────

/**
 * Minimal in-memory [DataStore] for migration tests. Uses a [MutableStateFlow]
 * of [Preferences] so that reads observe writes performed via [edit].
 * Implements only the surface that [AiApiKeyMigration] uses.
 */
private fun newDataStore(): DataStore<Preferences> {
    val state = kotlinx.coroutines.flow.MutableStateFlow<Preferences>(
        androidx.datastore.preferences.core.emptyPreferences(),
    )
    return object : DataStore<Preferences> {
        override val data: kotlinx.coroutines.flow.Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }
}

private suspend fun DataStore<Preferences>.seedLegacy(value: String) {
    edit { it[stringPreferencesKey(AiApiKeyMigration.LEGACY_DATASTORE_KEY)] = value }
}
