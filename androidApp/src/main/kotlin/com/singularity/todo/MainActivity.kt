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
import com.singularity.todo.core.network.SupabaseConfig
import com.singularity.todo.core.platform.PlatformContext
import com.singularity.todo.core.settings.SettingsRepository
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

        // Supabase config (from BuildConfig or user settings)
        // TODO: Replace with actual BuildConfig values
        val supabaseConfig: SupabaseConfig? = null  // Will use anonymous auth if null

        startKoin {
            modules(sharedModule(db, settingsRepository, supabaseConfig))
        }

        setContent {
            App()
        }
    }
}
