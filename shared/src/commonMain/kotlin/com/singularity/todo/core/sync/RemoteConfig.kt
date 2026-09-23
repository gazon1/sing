package com.singularity.todo.core.sync

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Stores the single remote configuration (Supabase URL + anon key).
 *
 * Row with `id = "default"` is created on first launch and updated on settings save.
 * Stored in Room (not SecureStorage) because it is not sensitive — the anon key is
 * designed to be public. Sensitive credentials (refresh tokens) live in [SecureStoragePort].
 *
 * @see SyncRepositoryImpl
 */
@Entity(tableName = "remote_configs")
data class RemoteConfigEntity(
    @PrimaryKey val id: String,               // always "default"
    @ColumnInfo("supabase_url") val supabaseUrl: String,
    @ColumnInfo("anon_key") val anonKey: String,
    @ColumnInfo("updated_at") val updatedAt: Long,  // epoch millis
)

/**
 * DAO for [RemoteConfigEntity].
 */
@Dao
interface RemoteConfigDao {
    @Query("SELECT * FROM remote_configs WHERE id = 'default'")
    fun watchDefault(): Flow<RemoteConfigEntity?>

    @Query("SELECT * FROM remote_configs WHERE id = 'default'")
    suspend fun getDefault(): RemoteConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RemoteConfigEntity)

    @Query("DELETE FROM remote_configs WHERE id = 'default'")
    suspend fun deleteDefault()
}

/**
 * Repository for sync remote configuration.
 */
interface RemoteConfigRepository {
    val defaultConfig: Flow<RemoteConfigEntity?>
    suspend fun getConfig(): RemoteConfigEntity?
    suspend fun saveConfig(supabaseUrl: String, anonKey: String)
    suspend fun deleteConfig()
}
