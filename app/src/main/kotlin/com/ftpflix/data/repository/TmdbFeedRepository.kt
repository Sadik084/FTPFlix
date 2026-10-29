package com.ftpflix.data.repository

import android.content.Context
import com.ftpflix.BuildConfig
import com.ftpflix.data.local.FeedItemDao
import com.ftpflix.data.local.FeedItemEntity
import com.ftpflix.data.remote.TmdbMovie
import com.ftpflix.data.remote.TmdbService
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TmdbFeedRepository @Inject constructor(
    private val tmdb: TmdbService,
    private val feedDao: FeedItemDao,
    @ApplicationContext private val context: Context
) {
    private val apiKey = BuildConfig.TMDB_API_KEY

    private val prefs = context.getSharedPreferences("feed_state", Context.MODE_PRIVATE)
    private val keyRefreshedAt = "refreshed_at"

    // Cached snapshot is stale after 12h.
    private val staleTtlMs = 12L * 60 * 60 * 1000

    fun lastRefreshedAt(): Long = prefs.getLong(keyRefreshedAt, 0L)

    suspend fun needsRefresh(): Boolean {
        if (feedDao.count() == 0) return true
        return System.currentTimeMillis() - lastRefreshedAt() > staleTtlMs
    }

    // Random page/sort per refresh so the feed varies; unreleased titles dropped.
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val today = LocalDate.now().toString()
        val page = Random.nextInt(1, 7)
        val items = mutableListOf<FeedItemEntity>()
        var rank = 0

        // All list calls run concurrently so snapshot time is the slowest call only.
        val movieSorts = listOf(
            "popularity.desc",
            "vote_count.desc",
            "primary_release_date.desc",
            "vote_average.desc",
            "revenue.desc"
        )
        val tvSorts = listOf(
            "popularity.desc",
            "vote_count.desc",
            "first_air_date.desc",
            "vote_average.desc"
        )
        val movieSort = movieSorts[Random.nextInt(movieSorts.size)]
        val tvSort = tvSorts[Random.nextInt(tvSorts.size)]

        coroutineScope {
            val movieGenresD = async { runCatching { tmdb.movieGenres(apiKey).genres }.getOrDefault(emptyList()) }
            val tvGenresD = async { runCatching { tmdb.tvGenres(apiKey).genres }.getOrDefault(emptyList()) }
            val trendingMoviesD = async { runCatching { tmdb.trending("movie", apiKey, page).results }.getOrDefault(emptyList()) }
            val trendingTvD = async { runCatching { tmdb.trending("tv", apiKey, page).results }.getOrDefault(emptyList()) }
            val popularMoviesD = async { runCatching { tmdb.discover("movie", apiKey, sortBy = movieSort, page = page).results }.getOrDefault(emptyList()) }
            val newMoviesD = async { runCatching { tmdb.discover("movie", apiKey, sortBy = "primary_release_date.desc", page = page).results }.getOrDefault(emptyList()) }
            val popularTvD = async { runCatching { tmdb.discover("tv", apiKey, sortBy = tvSort, page = page).results }.getOrDefault(emptyList()) }
            val newTvD = async { runCatching { tmdb.discover("tv", apiKey, sortBy = "first_air_date.desc", page = page).results }.getOrDefault(emptyList()) }

            val movieGenreNames = movieGenresD.await().associate { it.id to it.name }
            val tvGenreNames = tvGenresD.await().associate { it.id to it.name }

            fun isReleased(date: String?): Boolean = date == null || date <= today

            fun add(shelfId: String, m: TmdbMovie) {
                if (!isReleased(m.releaseDate)) return
                val title = m.title.ifBlank { m.name }
                if (title.isBlank()) return
                items += FeedItemEntity(
                    id = "$shelfId:${m.id}",
                    shelfId = shelfId,
                    rank = rank++,
                    kind = "movie",
                    tmdbId = m.id,
                    title = title,
                    year = m.releaseDate?.take(4)?.toIntOrNull(),
                    posterUrl = m.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                    backdropUrl = m.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
                    genres = m.genreIds.mapNotNull { movieGenreNames[it] }.joinToString(","),
                    popularity = m.popularity
                )
            }

            fun addSeries(shelfId: String, m: TmdbMovie) {
                if (!isReleased(m.firstAirDate)) return
                val title = m.name.ifBlank { m.title }
                if (title.isBlank()) return
                items += FeedItemEntity(
                    id = "$shelfId:${m.id}",
                    shelfId = shelfId,
                    rank = rank++,
                    kind = "series",
                    tmdbId = m.id,
                    title = title,
                    year = m.firstAirDate?.take(4)?.toIntOrNull(),
                    posterUrl = m.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                    backdropUrl = m.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
                    genres = m.genreIds.mapNotNull { tvGenreNames[it] }.joinToString(","),
                    popularity = m.popularity
                )
            }

            trendingMoviesD.await().forEach { add("trending", it) }
            trendingTvD.await().forEach { addSeries("trending_tv", it) }
            popularMoviesD.await().forEach { add("popular", it) }
            newMoviesD.await().forEach { add("new", it) }
            popularTvD.await().forEach { addSeries("popular_tv", it) }
            newTvD.await().forEach { addSeries("new_tv", it) }
        }

        // Never wipe the snapshot with an empty result (offline/API failure).
        if (items.isNotEmpty()) {
            feedDao.replaceAll(items.distinctBy { it.id }.take(140))
            prefs.edit { putLong(keyRefreshedAt, System.currentTimeMillis()) }
        }
    }

}
