package com.ftpflix.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ftpflix.BuildConfig
import com.ftpflix.core.config.AgeRatingStore
import com.ftpflix.core.config.RatingWarmup
import com.ftpflix.core.util.fromRouteToken
import com.ftpflix.data.model.CatalogEntry
import com.ftpflix.data.remote.TmdbMovie
import com.ftpflix.data.remote.TmdbPaged
import com.ftpflix.data.remote.TmdbService
import com.ftpflix.data.repository.FeedRepository
import com.ftpflix.ui.navigation.SeeAllRoute
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

// Backs a Home "See all" rail: cached snapshot, then paginated TMDB list.
@HiltViewModel
class SeeAllViewModel @Inject constructor(
    private val tmdb: TmdbService,
    private val feedRepository: FeedRepository,
    ageRatings: AgeRatingStore,
    private val warmup: RatingWarmup,
    savedState: SavedStateHandle,
) : ViewModel() {

    /** Poster age-rating tags keyed by media id ("movie:123"); empty when unknown. */
    val ageRatings: StateFlow<Map<String, String>> = ageRatings.ratings

    private val shelfId: String = runCatching {
        savedState.toRoute<SeeAllRoute>().shelfId.fromRouteToken()
    }.getOrDefault("")

    private val apiKey = BuildConfig.TMDB_API_KEY

    private val _items = MutableStateFlow<List<CatalogEntry>>(emptyList())
    val items: StateFlow<List<CatalogEntry>> = _items.asStateFlow()

    private val _loadingMore = MutableStateFlow(value = false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _initialLoading = MutableStateFlow(value = true)
    val initialLoading: StateFlow<Boolean> = _initialLoading.asStateFlow()

    private var page = 0
    private var totalPages = Int.MAX_VALUE
    private var genreId: Int? = null
    private var genreKind = "movie"
    private val seenIds = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            val seed = feedRepository.itemsForShelf(shelfId)
            synchronized(seenIds) { seenIds += seed.map(CatalogEntry::id) }
            _items.value = seed
            _initialLoading.value = false
            warmup.request(seed.map { it.id })
            if (shelfId.startsWith("genre_")) setupGenre(shelfId.removePrefix("genre_"), seed)
            loadMore()
        }
    }

    // Appends the next page; no-op while one is in flight or done.
    fun loadMore() {
        if (_loadingMore.value || _initialLoading.value || (page >= totalPages)) return
        _loadingMore.value = true
        viewModelScope.launch {
            try {
                fetchNextPage()
            } finally {
                _loadingMore.value = false
            }
        }
    }

    private suspend fun fetchNextPage() {
        val next = page + 1
        val (_, results, responseTotalPages, _) = runCatching { fetchPage(next) }.getOrNull()
            ?: return // retry on next scroll
        page = next
        totalPages = responseTotalPages.coerceAtLeast(1)
        if (results.isEmpty()) {
            totalPages = page
            return
        }
        val today = LocalDate.now().toString()
        val kind = shelfKind()
        val fresh = results.asSequence()
            .mapNotNull { it.toEntry(kind, today) }
            .filter { (id, _, _, _, _, _, _, _, _) -> synchronized(seenIds) { seenIds.add(id) } }
            .toList()
        if (fresh.isNotEmpty()) {
            _items.update { it + fresh }
            warmup.request(fresh.map { it.id })
        }
    }

    // Released titles only.
    private suspend fun fetchPage(page: Int): TmdbPaged<TmdbMovie> {
        val today = LocalDate.now().toString()
        return when (shelfId) {
            "trending" -> tmdb.trending("movie", apiKey, page)
            "trending_tv" -> tmdb.trending("tv", apiKey, page)
            "popular" -> tmdb.discover("movie", apiKey, sortBy = "popularity.desc", page = page, primaryReleaseDateLte = today)
            "popular_tv" -> tmdb.discover("tv", apiKey, sortBy = "popularity.desc", page = page, firstAirDateLte = today)
            "new" -> tmdb.discover("movie", apiKey, sortBy = "primary_release_date.desc", page = page, primaryReleaseDateLte = today)
            "new_tv" -> tmdb.discover("tv", apiKey, sortBy = "first_air_date.desc", page = page, firstAirDateLte = today)
            else -> tmdb.discover(
                genreKind, apiKey,
                sortBy = "popularity.desc", withGenres = genreId?.toString(), page = page,
                primaryReleaseDateLte = if (genreKind == "movie") today else null,
                firstAirDateLte = if (genreKind == "tv") today else null,
            )
        }
    }

    // Resolve a genre name to its TMDB id and pick the dominant kind.
    private suspend fun setupGenre(name: String, seed: List<CatalogEntry>) {
        genreId = runCatching {
            coroutineScope {
                val movies = async { runCatching { tmdb.movieGenres(apiKey).genres }.getOrDefault(emptyList()) }
                val shows = async { runCatching { tmdb.tvGenres(apiKey).genres }.getOrDefault(emptyList()) }
                (movies.await() + shows.await())
                    .firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
            }
        }.getOrNull()
        genreKind = if (seed.count { it.kind == "series" } > seed.count { it.kind == "movie" })
            "series" else "movie"
    }

    private fun shelfKind(): String = when (shelfId) {
        "trending_tv", "popular_tv", "new_tv" -> "series"
        else -> if (shelfId.startsWith("genre_")) genreKind else "movie"
    }

    // Same shape as the feed mapper; unreleased titles dropped.
    private fun TmdbMovie.toEntry(kind: String, today: String): CatalogEntry? {
        val date = if (kind == "series") firstAirDate else releaseDate
        if ((date != null) && (date > today)) return null
        val title = (if (kind == "series") name else title).ifBlank { return null }
        return CatalogEntry(
            id = "tmdb:${if (kind == "series") "tv" else "movie"}:$id",
            kind = kind,
            title = title,
            year = date?.take(4)?.toIntOrNull(),
            posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
            popularity = popularity.takeIf { it > 0 },
            hasFile = false,
        )
    }
}
