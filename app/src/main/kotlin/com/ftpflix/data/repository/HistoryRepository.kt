package com.ftpflix.data.repository

import com.ftpflix.data.local.HistoryDao
import com.ftpflix.data.local.HistoryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryRepository @Inject constructor(
    private val dao: HistoryDao
) {
    fun continueWatching(): Flow<List<HistoryEntity>> = dao.continueWatching()

    fun recent(): Flow<List<HistoryEntity>> = dao.recent()

    suspend fun save(
        mediaKey: String,
        groupKey: String,
        title: String,
        posterUrl: String?,
        streamUrl: String,
        positionMs: Long,
        durationMs: Long,
        episodeLabel: String? = null
    ) {
        dao.save(
            HistoryEntity(
                mediaKey = mediaKey,
                groupKey = groupKey,
                title = title,
                posterUrl = posterUrl,
                streamUrl = streamUrl,
                positionMs = positionMs,
                durationMs = durationMs,
                lastPlayedAt = System.currentTimeMillis(),
                episodeLabel = episodeLabel
            )
        )
    }

    suspend fun get(key: String): HistoryEntity? = dao.get(key)

    suspend fun remove(mediaKey: String) = dao.remove(mediaKey)

    suspend fun clear() = dao.clear()
}
