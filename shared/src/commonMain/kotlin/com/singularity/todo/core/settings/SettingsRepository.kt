package com.singularity.todo.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    companion object {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        val FONT_SIZE_SCALE = floatPreferencesKey("font_size_scale")
        val AI_API_KEY = stringPreferencesKey("ai_api_key")
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val AI_SYSTEM_PROMPT = stringPreferencesKey("ai_system_prompt")
        val USER_ID = stringPreferencesKey("user_id")
        const val DEFAULT_SYSTEM_PROMPT = "You are a helpful productivity assistant. Be concise and actionable."
    }

    val darkTheme: Flow<Boolean> = dataStore.data.map { it[DARK_THEME] ?: false }
    val accentColor: Flow<String> = dataStore.data.map { it[ACCENT_COLOR] ?: "blue" }
    val fontSizeScale: Flow<Float> = dataStore.data.map { it[FONT_SIZE_SCALE] ?: 1f }
    val aiApiKey: Flow<String?> = dataStore.data.map { it[AI_API_KEY] }
    val aiProvider: Flow<String> = dataStore.data.map { it[AI_PROVIDER] ?: "openai" }
    val aiModel: Flow<String> = dataStore.data.map { it[AI_MODEL] ?: "gpt-4o-mini" }
    val aiSystemPrompt: Flow<String> = dataStore.data.map { it[AI_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT }
    val userId: Flow<String> = dataStore.data.map { it[USER_ID] ?: "anonymous" }

    fun aiApiKeyBlocking(): String? = kotlinx.coroutines.runBlocking { dataStore.data.first()[AI_API_KEY] }
    fun aiModelBlocking(): String = kotlinx.coroutines.runBlocking { dataStore.data.first()[AI_MODEL] } ?: "gpt-4o-mini"
    fun userIdBlocking(): String = kotlinx.coroutines.runBlocking { dataStore.data.first()[USER_ID] } ?: "anonymous"

    suspend fun setDarkTheme(value: Boolean) { dataStore.edit { it[DARK_THEME] = value } }
    suspend fun setAccentColor(value: String) { dataStore.edit { it[ACCENT_COLOR] = value } }
    suspend fun setFontSizeScale(value: Float) { dataStore.edit { it[FONT_SIZE_SCALE] = value } }
    suspend fun setAiApiKey(value: String) { dataStore.edit { it[AI_API_KEY] = value } }
    suspend fun setAiProvider(value: String) { dataStore.edit { it[AI_PROVIDER] = value } }
    suspend fun setAiModel(value: String) { dataStore.edit { it[AI_MODEL] = value } }
    suspend fun setUserId(value: String) { dataStore.edit { it[USER_ID] = value } }
}
