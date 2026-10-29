package com.ftpflix.data.repository

import com.ftpflix.data.local.FeedItemDao
import com.ftpflix.data.model.CatalogEntry
import com.ftpflix.domain.feed.Shelf
import com.ftpflix.domain.feed.shelfTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeedRepository @Inject constructor(
    private val feedDao: FeedItemDao
) {
    fun shelvesFlow(): Flow<List<Shelf>> = feedDao.all().map { items ->
        items
            .groupBy { it.shelfId }
            .map { (shelfId, groupItems) ->
                Shelf(
                    id = shelfId,
                    title = shelfTitle(shelfId),
                    items = groupItems.sortedBy { it.rank }.map { it.toEntry() }
                )
            }
            .sortedBy { shelfOrder.indexOf(it.id).takeIf { idx -> idx >= 0 } ?: Int.MAX_VALUE }
    }

    // Snapshot for one shelf; seeds See-All while its VM fetches the full list.
    suspend fun itemsForShelf(shelfId: String): List<CatalogEntry> =
        feedDao.all().first()
            .filter { it.shelfId == shelfId }
            .sortedBy { it.rank }
            .map { it.toEntry() }

    private fun com.ftpflix.data.local.FeedItemEntity.toEntry(): CatalogEntry = CatalogEntry(
        id = "tmdb:${if (kind == "series") "tv" else "movie"}:${tmdbId}",
        kind = kind,
        title = title,
        year = year,
        posterUrl = posterUrl,
        genres = genres.split(",").filter { it.isNotBlank() },
        popularity = popularity.takeIf { it > 0 },
        imdbId = null,
        hasFile = true
    )

    companion object {
        private val shelfOrder = listOf("trending", "trending_tv", "popular", "popular_tv", "new", "new_tv")
    }
}
