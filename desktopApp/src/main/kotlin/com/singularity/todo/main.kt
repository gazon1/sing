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
import com.singularity.todo.core.settings.SettingsRepository
import org.koin.core.context.startKoin
import java.io.File

private fun createDesktopDataStore(): DataStore<Preferences> =
    PreferenceDataStoreFactory.create { File("${System.getProperty("user.home")}/.singularity-todo/settings.preferences_pb") }

fun main() = singleWindowApplication(
    title = "Singularity Todo"
) {
    val dbPath = "${System.getProperty("user.home")}/.singularity-todo/singularity-todo.db"
    File(dbPath).parentFile?.mkdirs()

    // Desktop database using SQLite via JDBC (JvmDatabase)
    // Android uses Room's generated AppDatabase_Impl via KSP
    val db = JvmDatabase.create(dbPath)

    // Build settings repository
    val settingsRepository = SettingsRepository(createDesktopDataStore())

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
