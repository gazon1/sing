package com.singularity.todo

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.di.sharedModule
import com.singularity.todo.core.files.AndroidFileSystem
import com.singularity.todo.core.backup.AndroidBackupCodec
import com.singularity.todo.core.network.SupabaseConfig
import com.singularity.todo.core.notifications.AndroidNotificationPort
import com.singularity.todo.core.platform.PlatformContext
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.security.AndroidSecureStorage
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.notifications.NotificationPort
import com.singularity.todo.feature.reminders.ReminderRepository
import com.singularity.todo.feature.reminders.RoomReminderRepository
import org.koin.core.context.startKoin

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        PlatformContext.initialize(this)

        // Build database with auto-migration (schema export enabled in AppDatabase)
        val db = Room.databaseBuilder<AppDatabase>(
            applicationContext,
            AppDatabase::class.java,
            "singularity-todo.db"
        )
            .build()

        // Build DataStore
        val settingsDataStore = applicationContext.dataStore

        // Build settings repository
        val settingsRepository = SettingsRepository(settingsDataStore)

        // Secure storage (EncryptedSharedPreferences on Android)
        val secureStorage: SecureStoragePort = AndroidSecureStorage(applicationContext)

        // Notifications
        val notificationPort: NotificationPort = AndroidNotificationPort(applicationContext)

        // Reminders
        val reminderRepository: ReminderRepository = RoomReminderRepository(db.reminderDao())

        // FileSystem for attachments
        val fileSystem = AndroidFileSystem(applicationContext)
        val attachmentsDir = "${applicationContext.filesDir}/attachments"
        val backupDir = "${applicationContext.filesDir}/backups"

        // Supabase config (from BuildConfig or user settings)
        // TODO: Replace with actual BuildConfig values
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
                    backupCodec = AndroidBackupCodec()
                )
            )
        }

        setContent {
            App()
        }
    }
}
