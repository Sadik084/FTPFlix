package com.ftpflix.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ResolvedStreamDao {

    @Query("SELECT * FROM resolved_streams WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): ResolvedStreamEntity?

    @Query("SELECT * FROM resolved_streams ORDER BY resolvedAt DESC LIMIT 100")
    fun recent(): Flow<List<ResolvedStreamEntity>>

    @Upsert
    suspend fun upsert(entity: ResolvedStreamEntity)

    @Query("DELETE FROM resolved_streams")
    suspend fun clear()
}
