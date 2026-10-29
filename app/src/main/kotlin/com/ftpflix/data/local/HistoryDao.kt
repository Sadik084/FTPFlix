package com.ftpflix.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    // Continue Watching includes finished items (Watched badge); one row per groupKey; sub-5s blips drop out.
    @Query(
        """SELECT h.* FROM history h
           INNER JOIN (
             SELECT groupKey, MAX(lastPlayedAt) AS maxTime
             FROM history
             GROUP BY groupKey
           ) t ON h.groupKey = t.groupKey AND h.lastPlayedAt = t.maxTime
           WHERE h.positionMs > 5000
           ORDER BY h.lastPlayedAt DESC LIMIT 20"""
    )
    fun continueWatching(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE mediaKey = :key")
    suspend fun get(key: String): HistoryEntity?

    @Query("DELETE FROM history WHERE mediaKey = :key")
    suspend fun remove(key: String)

    @Query("SELECT * FROM history ORDER BY lastPlayedAt DESC LIMIT 50")
    fun recent(): Flow<List<HistoryEntity>>

    @Upsert
    suspend fun save(entity: HistoryEntity)

    @Query("DELETE FROM history")
    suspend fun clear()
}
