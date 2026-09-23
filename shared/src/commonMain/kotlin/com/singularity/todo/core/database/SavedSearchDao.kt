package com.singularity.todo.core.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedSearchDao {

    @Query("SELECT * FROM saved_searches WHERE user_id = :userId ORDER BY name ASC")
    fun watchAll(userId: String): Flow<List<SavedSearchEntity>>

    @Query("SELECT * FROM saved_searches WHERE user_id = :userId AND id = :id")
    fun watchById(userId: String, id: String): Flow<SavedSearchEntity?>

    @Query("SELECT * FROM saved_searches WHERE user_id = :userId AND id = :id")
    suspend fun getById(userId: String, id: String): SavedSearchEntity?

    @Query("SELECT * FROM saved_searches WHERE user_id = :userId AND lower(name) = lower(:name) LIMIT 1")
    suspend fun findByName(userId: String, name: String): SavedSearchEntity?

    @Upsert(entity = SavedSearchEntity::class)
    suspend fun upsert(entity: SavedSearchEntity)

    @Query("DELETE FROM saved_searches WHERE user_id = :userId AND id = :id")
    suspend fun delete(userId: String, id: String)
}
