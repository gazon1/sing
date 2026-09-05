package com.singularity.todo.core.di

import com.singularity.todo.core.backup.BackupFileNamer
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.tasks.AttachmentSaver
import com.singularity.todo.feature.checklist.ChecklistRepository
import com.singularity.todo.feature.pomodoro.PomodoroRepository
import org.junit.Test

/**
 * Smoke-test the DI graph on JVM.
 *
 * Registers both `coreDomainModule` and `platformModule` so that
 * DAO-based repositories (ChecklistRepository, PomodoroRepository) can
 * resolve their database dependencies on JVM.
 *
 * Run with: ./gradlew :shared:jvmTest --tests "com.singularity.todo.core.di.DiGraphTest"
 */
class DiGraphTest {

    @Test
    fun `core domain + platform modules register without errors`() {
        val app = org.koin.core.context.startKoin {
            modules(coreDomainModule(), platformModule())
        }
        try {
            app.koin.get<SettingsRepository>()
            app.koin.get<ChecklistRepository>()
            app.koin.get<PomodoroRepository>()
        } finally {
            org.koin.core.context.stopKoin()
        }
    }

    @Test
    fun `UI ports are registered in DI graph`() {
        val app = org.koin.core.context.startKoin {
            modules(coreDomainModule(), platformModule())
        }
        try {
            app.koin.get<IdGenerator>()
            app.koin.get<TimeZoneProvider>()
            app.koin.get<BackupFileNamer>()
            app.koin.get<AttachmentSaver>()
        } finally {
            org.koin.core.context.stopKoin()
        }
    }
}
