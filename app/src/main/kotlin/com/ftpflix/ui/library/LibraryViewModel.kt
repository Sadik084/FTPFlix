package com.ftpflix.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ftpflix.BuildConfig
import com.ftpflix.core.config.AgeRatingStore
import com.ftpflix.core.config.RatingWarmup
import com.ftpflix.data.local.FeedItemDao
import com.ftpflix.data.local.FeedItemEntity
import com.ftpflix.data.remote.TmdbGenre
import com.ftpflix.data.remote.TmdbMovie
import com.ftpflix.data.remote.TmdbService
import com.ftpflix.data.repository.TmdbFeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

enum class LibraryTab { Movies, Series }

/** TMDB genre ids per name (movie and tv id spaces differ). */
private data class GenreIds(val movieId: Int?, val tvId: Int?)

/** Library browse: seeded from the cached feed snapshot, then extended with paginated TMDB discover pages as the user scrolls. Genre filtering applies to the seed by name and to network pages by genre id; paging stops at the last page and never re-requests. */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val tmdb: TmdbService,
    feedDao: FeedItemDao,
    private val feedRepo: TmdbFeedRepository,
    ageRatings: AgeRatingStore,
    private val warmup: RatingWarmup,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    /** Poster age-rating tags keyed by media id ("movie:123"); empty when unknown. */
    val ageRatings: StateFlow<Map<String, String>> = ageRatings.ratings

    private val apiKey = BuildConfig.TMDB_API_KEY

    private val _tab = MutableStateFlow(
        savedState.get<String>(KEY_TAB)?.let { name ->
            runCatching { LibraryTab.valueOf(name) }.getOrNull()
        } ?: LibraryTab.Movies,
    )
    val tab: StateFlow<LibraryTab> = _tab.asStateFlow()

    /** Selected genre names; empty set = all genres. */
    private val _genres = MutableStateFlow(parseSavedGenres())
    val selectedGenres: StateFlow<Set<String>> = _genres.asStateFlow()

    private val _refreshing = MutableStateFlow(value = false)
    val isRefreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _items = MutableStateFlow<List<FeedItemEntity>>(emptyList())
    val items: StateFlow<List<FeedItemEntity>> = _items.asStateFlow()

    /** True until the first content (seed or page) lands for the current filters. */
    private val _initialLoading = MutableStateFlow(value = true)
    val initialLoading: StateFlow<Boolean> = _initialLoading.asStateFlow()

    private val _loadingMore = MutableStateFlow(value = false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val genres: StateFlow<List<String>> = combine(feedDao.all(), _tab, _genres) { list, tab, selected ->
        val names = list.asSequence()
            .filter { it.inTab(tab) }
            .flatMap { it.genreTokens() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()
        names + selected.filter { it !in names }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var page = 0
    private var totalPages = Int.MAX_VALUE
    private var endReached = false
    private val seenKeys = mutableSetOf<String>()
    private var loadJob: Job? = null
    private var genreMap: Map<String, GenreIds>? = null

    init {
        viewModelScope.launch {
            combine(feedDao.all(), _tab, _genres) { list, tab, selected ->
                Triple(list, tab, selected)
            }.distinctUntilChanged().collect { (list, tab, selected) ->
                reseed(list, tab, selected)
            }
        }
    }

    fun setTab(value: LibraryTab) {
        _tab.value = value
        savedState[KEY_TAB] = value.name
    }

    /** Toggles a genre filter; empty name clears all selections. */
    fun toggleGenre(name: String) {
        val next = if (name.isBlank()) {
            emptySet()
        } else {
            val current = _genres.value
            if (name in current) current - name else current + name
        }
        _genres.value = next
        savedState[KEY_GENRES] = next.joinToString(",")
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                feedRepo.refresh()
            } catch (_: Exception) {
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Appends the next discover page; no-op while a page is in flight or done. */
    fun loadMore() {
        if (_loadingMore.value || endReached || (page >= totalPages)) return
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _loadingMore.value = true
            try {
                fetchNextPage()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _error.value = "Couldn't load more titles. Check your connection."
            } finally {
                _loadingMore.value = false
                _initialLoading.value = false
            }
        }
    }

    /** Retries the failed page load. */
    fun retry() {
        _error.value = null
        loadMore()
    }

    private fun reseed(list: List<FeedItemEntity>, tab: LibraryTab, selected: Set<String>) {
        loadJob?.cancel()
        page = 0
        totalPages = Int.MAX_VALUE
        endReached = false
        _error.value = null
        seenKeys.clear()
        val seed = list.asSequence()
            .filter { it.inTab(tab) }
            .filter { it.hasAnyGenre(selected) }
            .distinctBy { it.entryKey() }
            .toList()
        seenKeys += seed.map { it.entryKey() }
        _items.value = seed
        requestWarmup()
        _initialLoading.value = seed.isEmpty()
        loadMore()
    }

    private suspend fun fetchNextPage() {
        val tab = _tab.value
        val selected = _genres.value
        val genreIds = genreIdsFor(tab, selected)
        if (selected.isNotEmpty() && (genreIds == null)) {
            // Genre id unknown (e.g. offline): the snapshot filter is all we can offer.
            endReached = true
            return
        }
        val next = page + 1
        val kind = kindFor(tab)
        val today = LocalDate.now().toString()
        val (_, results, responseTotalPages, _) = tmdb.discover(
            kind, apiKey,
            sortBy = "popularity.desc",
            withGenres = genreIds?.takeIf { it.isNotEmpty() }?.joinToString(","),
            page = next,
            primaryReleaseDateLte = if (kind == "movie") today else null,
            firstAirDateLte = if (kind == "tv") today else null,
        )
        page = next
        totalPages = responseTotalPages.coerceAtLeast(1)
        if (results.isEmpty() || (page >= totalPages)) endReached = true
        var rank = _items.value.size
        val fresh = results.asSequence()
            .mapNotNull { it.toFeedItem(kind, today, rank++) }
            .filter { seenKeys.add(it.entryKey()) }
            .toList()
        if (fresh.isNotEmpty()) {
            _items.update { it + fresh }
            requestWarmup()
        }
    }

    private fun FeedItemEntity.inTab(tab: LibraryTab): Boolean = kind == kindFor(tab)

    private fun FeedItemEntity.genreTokens(): List<String> = genres.split(',')

    /** Empty selection = all; else require at least one selected genre (case-insensitive). */
    private fun FeedItemEntity.hasAnyGenre(selected: Set<String>): Boolean =
        selected.isEmpty() || genres.split(',').any { g ->
            selected.any { it.equals(g.trim(), ignoreCase = true) }
        }

    /** Resolves ids for all selected genres; null when any is unknown (offline). */
    private suspend fun genreIdsFor(tab: LibraryTab, names: Set<String>): List<Int>? {
        if (names.isEmpty()) return emptyList()
        val map = genreMap ?: loadGenreMap().also { genreMap = it }
        val ids = names.mapNotNull { name ->
            when (tab) {
                LibraryTab.Movies -> map[name]?.movieId
                LibraryTab.Series -> map[name]?.tvId
            }
        }
        return ids.takeIf { it.size == names.size }
    }

    private suspend fun loadGenreMap(): Map<String, GenreIds> = coroutineScope {
        val moviesD = async { runCatching { tmdb.movieGenres(apiKey).genres }.getOrDefault(emptyList()) }
        val tvD = async { runCatching { tmdb.tvGenres(apiKey).genres }.getOrDefault(emptyList()) }
        val movies = moviesD.await()
        val tv = tvD.await()
        (movies.map(TmdbGenre::name) + tv.map(TmdbGenre::name)).asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .associateWith { name ->
                GenreIds(
                    movieId = movies.firstOrNull { it.isNamed(name) }?.id,
                    tvId = tv.firstOrNull { it.isNamed(name) }?.id,
                )
            }
    }

    private fun FeedItemEntity.entryKey(): String = "$kind:$tmdbId"

    /** Queue the visible titles' age ratings for background enrichment. */
    private fun requestWarmup() {
        val ids = _items.value.map { (_, _, _, kind, tmdbId, _, _, _, _, _, _) ->
            "${if (kind == "series") "tv" else "movie"}:$tmdbId"
        }
        warmup.request(ids)
    }

    private fun TmdbGenre.isNamed(query: String): Boolean = name == query

    /** Same shape as the feed mapper; unreleased titles are dropped. */
    private fun TmdbMovie.toFeedItem(kind: String, today: String, rank: Int): FeedItemEntity? {
        val date = if (kind == "series") firstAirDate else releaseDate
        if ((date != null) && (date > today)) return null
        val title = (if (kind == "series") name else title).ifBlank { return null }
        return FeedItemEntity(
            id = "library_paged:$kind:$id",
            shelfId = "library_paged",
            rank = rank,
            kind = kind,
            tmdbId = id,
            title = title,
            year = date?.take(4)?.toIntOrNull(),
            posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
            backdropUrl = backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
            genres = "",
            popularity = popularity,
        )
    }

    private companion object {
        private const val KEY_TAB = "library_tab"
        private const val KEY_GENRES = "library_genres"
    }

    private fun parseSavedGenres(): Set<String> =
        savedState.get<String>(KEY_GENRES)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
}

private fun kindFor(tab: LibraryTab): String = when (tab) {
    LibraryTab.Movies -> "movie"
    LibraryTab.Series -> "series"
}
