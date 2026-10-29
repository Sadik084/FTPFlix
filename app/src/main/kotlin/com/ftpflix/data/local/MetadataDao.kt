package com.ftpflix.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface MetadataDao {

    @Query("SELECT * FROM metadata_details")
    fun all(): kotlinx.coroutines.flow.Flow<List<MetadataEntity>>

    @Query("SELECT * FROM metadata_details")
    suspend fun allOnce(): List<MetadataEntity>

    @Query("SELECT * FROM metadata_details WHERE provider = :provider AND id = :id")
    suspend fun get(provider: String, id: String): MetadataEntity?

    @Upsert
    suspend fun upsert(entity: MetadataEntity)

    @Query("UPDATE metadata_details SET failedAt = :now WHERE provider = :provider AND id = :id")
    suspend fun markFailed(provider: String, id: String, now: Long)

    @Query("UPDATE metadata_details SET ageRating = :ageRating WHERE provider = :provider AND id = :id")
    suspend fun updateAgeRating(provider: String, id: String, ageRating: String)

    @Query("SELECT (failedAt IS NOT NULL AND failedAt > :windowStart) FROM metadata_details WHERE provider = :provider AND id = :id")
    suspend fun isRecentlyFailed(provider: String, id: String, windowStart: Long): Boolean

    @Query("DELETE FROM metadata_details WHERE provider = :provider")
    suspend fun clearProvider(provider: String)

    @Query("DELETE FROM metadata_details")
    suspend fun clear()
}
