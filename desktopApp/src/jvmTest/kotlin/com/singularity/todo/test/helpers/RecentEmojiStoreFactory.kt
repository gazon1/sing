package com.singularity.todo.test.helpers

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.singularity.todo.feature.projects.data.RecentEmojiStore
import java.nio.file.Files

/**
 * A [RecentEmojiStore] over a preferences file of its own.
 *
 * A real file rather than an in-memory double: the sheet's own reaction to a
 * write — the history appearing — is the thing under test, and a double that
 * answers from memory would answer it in a way the real store does not.
 */
fun newRecentEmojiStore(): RecentEmojiStore {
    val dir = Files.createTempDirectory("recent-emoji-store")
    val file = dir.resolve("recent.preferences_pb").toFile()
    return RecentEmojiStore(PreferenceDataStoreFactory.create { file })
}
