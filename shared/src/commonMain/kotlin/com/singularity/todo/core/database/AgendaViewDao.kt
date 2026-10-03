package com.singularity.todo.core.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AgendaViewDao {

    @Query("SELECT * FROM agenda_views WHERE user_id = :userId ORDER BY name ASC")
    fun watchAll(userId: String): Flow<List<AgendaViewEntity>>

    @Query("SELECT * FROM agenda_views WHERE user_id = :userId AND id = :id")
    fun watchById(userId: String, id: String): Flow<AgendaViewEntity?>

    @Query("SELECT * FROM agenda_views WHERE user_id = :userId AND id = :id")
    suspend fun getById(userId: String, id: String): AgendaViewEntity?

    @Upsert(entity = AgendaViewEntity::class)
    suspend fun upsert(entity: AgendaViewEntity)

    @Query("DELETE FROM agenda_views WHERE user_id = :userId AND id = :id")
    suspend fun delete(userId: String, id: String)

    @Query("SELECT * FROM agenda_views WHERE user_id = :userId")
    suspend fun listAllForUser(userId: String): List<AgendaViewEntity>
}
