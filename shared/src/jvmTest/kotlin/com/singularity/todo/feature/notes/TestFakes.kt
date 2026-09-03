package com.singularity.todo.feature.notes

import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Minimal test double for [SettingsRepository].
 * Only implements what [NotesViewModel] actually uses: [userIdBlocking].
 */
class FakeSettingsRepository(userId: UserId) : SettingsRepository(FakeDataStore()) {
    private val _userId = userId.value

    override val userId: Flow<String> = MutableStateFlow(_userId)
    override fun userIdBlocking() = _userId
}

/** Fake DataStore that returns empty preferences — never actually read in tests */
private class FakeDataStore : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
    private val prefs = androidx.datastore.preferences.core.emptyPreferences()
    override val data: Flow<androidx.datastore.preferences.core.Preferences> =
        kotlinx.coroutines.flow.flowOf(prefs)
    override suspend fun updateData(
        transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences
    ): androidx.datastore.preferences.core.Preferences = prefs
}

/** Pass-through HTML port for tests that don't need conversion verification */
class FakeMarkdownHtmlPort : MarkdownHtmlPort {
    override fun toHtml(md: String) = "<p>$md</p>"
    override fun toMarkdown(html: String) = html.trim().removePrefix("<p>").removeSuffix("</p>")
}
