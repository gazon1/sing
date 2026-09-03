package com.singularity.todo.core.di

import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.backup.JvmBackupCodec
import com.singularity.todo.core.database.JvmDatabase
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.notifications.JvmNotificationPort
import com.singularity.todo.core.notifications.NotificationPort
import com.singularity.todo.core.security.JvmSecureStorage
import com.singularity.todo.core.security.SecureStoragePort
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * JVM/desktop platform bindings.
 *
 * - [JvmDatabase] is created here and provides DAOs directly (no reflection)
 * - [SecureStoragePort] → [JvmSecureStorage]
 * - [NotificationPort] → [JvmNotificationPort]
 * - [FileSystem] → [JvmFileSystem]
 * - [BackupCodec] → [JvmBackupCodec]
 * - [PromptExecutor] → real OkHttp+OpenAI via [createKoogPromptExecutor]
 * - [androidx.datastore.core.DataStore] → in-memory stub for JVM
 */
actual fun platformModule(): Module = module {
    // ─── Database ───────────────────────────────────────────────────────

    single<JvmDatabase> {
        val dbPath = System.getProperty("user.home") + "/.singularity-todo/singularity-todo.db"
        java.io.File(dbPath).parentFile?.mkdirs()
        JvmDatabase.create(dbPath)
    }

    single { get<JvmDatabase>().taskDao() }
    single { get<JvmDatabase>().noteDao() }
    single { get<JvmDatabase>().projectDao() }
    single { get<JvmDatabase>().tagDao() }
    single { get<JvmDatabase>().syncOutboxDao() }
    single { get<JvmDatabase>().attachmentDao() }
    single { get<JvmDatabase>().reminderDao() }

    // ─── DataStore ──────────────────────────────────────────────────────

    single<androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>> {
        object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            private val prefs = androidx.datastore.preferences.core.emptyPreferences()
            override val data: kotlinx.coroutines.flow.Flow<androidx.datastore.preferences.core.Preferences> =
                kotlinx.coroutines.flow.flowOf(prefs)
            override suspend fun updateData(
                transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences
            ): androidx.datastore.preferences.core.Preferences = transform(prefs)
        }
    }

    // ─── Platform Ports ────────────────────────────────────────────────

    single<SecureStoragePort> { JvmSecureStorage() }

    single<NotificationPort> { JvmNotificationPort() }

    single<FileSystem> { JvmFileSystem() }

    single<BackupCodec> { JvmBackupCodec() }
}
