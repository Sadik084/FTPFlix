package com.ftpflix.data.repository

import com.ftpflix.data.local.MyListDao
import com.ftpflix.data.local.MyListEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MyListRepository @Inject constructor(
    private val dao: MyListDao
) {
    fun all(): Flow<List<MyListEntity>> = dao.all()

    suspend fun contains(key: String): Boolean = dao.get(key) != null

    suspend fun add(key: String, kind: String, title: String, year: Int?, posterUrl: String?) {
        dao.upsert(
            MyListEntity(
                key = key,
                kind = kind,
                title = title,
                year = year,
                posterUrl = posterUrl,
                addedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun remove(key: String) = dao.remove(key)

    suspend fun clear() = dao.clear()
}
