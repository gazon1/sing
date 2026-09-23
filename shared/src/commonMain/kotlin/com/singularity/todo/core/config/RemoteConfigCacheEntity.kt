package com.singularity.todo.core.config

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room entity for persisting the last-known-good [RemoteConfigSnapshot].
 *
 * Single-row cache: the row with `id = "default"` holds the last successfully
 * fetched snapshot. Stored as a JSON string so adding new fields to
 * [RemoteConfigSnapshot] does not require a schema migration.
 *
 * TTL is enforced by [RemoteConfigRepositoryImpl] (6-hour default) —
 * this entity does not self-expire.
 *
 * @see RemoteConfigPort
 * @see RemoteConfigRepositoryImpl
 */
@Entity(tableName = "remote_config_cache")
data class RemoteConfigCacheEntity(
    @PrimaryKey val id: String = "default",
    val snapshotJson: String,
    @ColumnInfo("fetched_at_epoch_millis") val fetchedAtEpochMillis: Long,
)

/**
 * DAO for [RemoteConfigCacheEntity].
 */
@Dao
interface RemoteConfigCacheDao {

    @Query("SELECT * FROM remote_config_cache WHERE id = 'default'")
    fun watchDefault(): Flow<RemoteConfigCacheEntity?>

    @Query("SELECT * FROM remote_config_cache WHERE id = 'default'")
    suspend fun getDefault(): RemoteConfigCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RemoteConfigCacheEntity)

    @Query("DELETE FROM remote_config_cache WHERE id = 'default'")
    suspend fun deleteDefault()
}
