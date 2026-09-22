package com.singularity.todo.core.di

import com.singularity.todo.core.backup.DefaultBackupFileNamer
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.checklist.ChecklistRepository
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.tasks.domain.model.AttachmentSaver
import kotlin.test.Test

/**
 * Smoke-test the DI graph on JVM.
 *
 * Registers both `domainModule` and `platformModule` so that
 * DAO-based repositories (ChecklistRepository, ReminderScheduler) can
 * resolve their database dependencies on JVM.
 *
 * Run with: ./gradlew :shared:jvmTest --tests "com.singularity.todo.core.di.DiGraphTest"
 */
class DiGraphTest {

    @Test
    fun `core domain + platform modules register without errors`() {
        val app = org.koin.dsl.koinApplication {
            modules(platformModule(), *domainModule().toTypedArray())
        }
        try {
            app.koin.get<SettingsRepository>()
            app.koin.get<ChecklistRepository>()
            app.koin.get<ReminderScheduler>()
            app.koin.get<BackupRepository>()
        } finally {
            app.close()
        }
    }

    @Test
    fun `UI ports are registered in DI graph`() {
        val app = org.koin.dsl.koinApplication {
            modules(platformModule(), *domainModule().toTypedArray())
        }
        try {
            app.koin.get<IdGenerator>()
            app.koin.get<TimeZoneProvider>()
            app.koin.get<DefaultBackupFileNamer>()
            app.koin.get<AttachmentSaver>()
        } finally {
            app.close()
        }
    }
}
