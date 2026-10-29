package com.ftpflix.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MyListDao {

    @Query("SELECT * FROM my_list ORDER BY addedAt DESC")
    fun all(): Flow<List<MyListEntity>>

    @Query("SELECT * FROM my_list WHERE key = :key")
    suspend fun get(key: String): MyListEntity?

    @Upsert
    suspend fun upsert(entity: MyListEntity)

    @Query("DELETE FROM my_list WHERE key = :key")
    suspend fun remove(key: String)

    @Query("DELETE FROM my_list")
    suspend fun clear()
}
