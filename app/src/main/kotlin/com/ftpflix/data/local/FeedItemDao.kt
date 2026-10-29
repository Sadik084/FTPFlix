package com.ftpflix.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedItemDao {

    @Query("SELECT * FROM feed_snapshot ORDER BY shelfId ASC, rank ASC")
    fun all(): Flow<List<FeedItemEntity>>

    @Query("SELECT COUNT(*) FROM feed_snapshot")
    suspend fun count(): Int

    @Query("DELETE FROM feed_snapshot")
    suspend fun clear()

    @Insert
    suspend fun insertAll(items: List<FeedItemEntity>)

    /** Replace the whole snapshot atomically. */
    @Transaction
    suspend fun replaceAll(items: List<FeedItemEntity>) {
        clear()
        if (items.isNotEmpty()) insertAll(items)
    }
}
