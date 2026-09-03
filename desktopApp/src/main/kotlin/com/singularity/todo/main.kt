package com.singularity.todo

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.window.singleWindowApplication
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.singularity.todo.core.backup.JvmBackupCodec
import com.singularity.todo.core.database.JvmDatabase
import com.singularity.todo.core.di.sharedModule
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.network.SupabaseConfig
import com.singularity.todo.core.notifications.JvmNotificationPort
import com.singularity.todo.core.notifications.NotificationPort
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.security.JvmSecureStorage
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.feature.reminders.ReminderRepository
import com.singularity.todo.feature.reminders.RoomReminderRepository
import org.koin.core.context.startKoin
import java.io.File

private fun createDesktopDataStore(): DataStore<Preferences> =
    PreferenceDataStoreFactory.create { File("${System.getProperty("user.home")}/.singularity-todo/settings.preferences_pb") }

fun main() = singleWindowApplication(
    title = "Singularity Todo"
) {
    val dbPath = "${System.getProperty("user.home")}/.singularity-todo/singularity-todo.db"
    File(dbPath).parentFile?.mkdirs()

    // Desktop database using SQLite via JDBC
    val db = JvmDatabase.create(dbPath)

    // Build settings repository
    val settingsRepository = SettingsRepository(createDesktopDataStore())

    // Secure storage (secret-tool on Linux, AES-GCM file fallback)
    val secureStorage: SecureStoragePort = JvmSecureStorage()

    // Notifications (notify-send on Linux)
    val notificationPort: NotificationPort = JvmNotificationPort()

    // Reminders
    val reminderRepository: ReminderRepository = RoomReminderRepository(db.reminderDao())

    // FileSystem for attachments
    val fileSystem = JvmFileSystem()
    val attachmentsDir = "${System.getProperty("user.home")}/.singularity-todo/attachments"
    val backupDir = "${System.getProperty("user.home")}/.singularity-todo/backups"

    // Ensure directories exist
    File(attachmentsDir).mkdirs()
    File(backupDir).mkdirs()

    // Supabase config (null on desktop until SDK integrated)
    val supabaseConfig: SupabaseConfig? = null

    startKoin {
        modules(
            sharedModule(
                database = db,
                settingsRepository = settingsRepository,
                secureStorage = secureStorage,
                notificationPort = notificationPort,
                reminderRepository = reminderRepository,
                supabaseConfig = supabaseConfig,
                attachmentsDir = attachmentsDir,
                backupDir = backupDir,
                fs = fileSystem,
                backupCodec = JvmBackupCodec()
            )
        )
    }

    MaterialTheme {
        Surface {
            App()
        }
    }
}
