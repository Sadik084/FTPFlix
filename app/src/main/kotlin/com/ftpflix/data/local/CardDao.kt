package com.ftpflix.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CardDao {

    @Query("SELECT * FROM cards WHERE key = :key")
    suspend fun card(key: String): CardEntity?

    @Query("SELECT * FROM cards")
    fun all(): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards")
    suspend fun allOnce(): List<CardEntity>

    @Upsert
    suspend fun upsert(card: CardEntity)

    /** Upserts a failure row so the negative cache also fills for new keys. */
    @Transaction
    suspend fun markFailed(key: String, now: Long) {
        val existing = card(key)
        upsert(
            existing?.copy(failedAt = now) ?: CardEntity(
                key = key, imdbId = null, title = key, year = null,
                posterUrl = null, matchedAt = now, failedAt = now
            )
        )
    }

    @Query("SELECT (failedAt IS NOT NULL AND failedAt > :windowStart) FROM cards WHERE key = :key")
    suspend fun isRecentlyFailed(key: String, windowStart: Long): Boolean

    @Query("DELETE FROM cards")
    suspend fun clear()
}
