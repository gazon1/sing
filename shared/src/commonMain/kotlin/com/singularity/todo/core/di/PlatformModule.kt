package com.singularity.todo.core.di

import org.koin.core.module.Module

/**
 * Platform-specific bindings:
 * - Database DAOs (TaskDao, NoteDao, ProjectDao, TagDao, SyncOutboxDao, AttachmentDao, ReminderDao)
 * - [com.singularity.todo.core.security.SecureStoragePort]
 * - [com.singularity.todo.core.notifications.NotificationPort]
 * - [com.singularity.todo.core.files.FileSystem]
 * - [com.singularity.todo.core.backup.BackupCodec]
 * - [com.singularity.todo.core.observability.CrashReportingPort]
 * - [ai.koog.prompt.executor.model.PromptExecutor]
 * - [androidx.datastore.core.DataStore] — Android: Preferences DataStore; JVM: FileDataStore
 */
expect fun platformModule(): Module
