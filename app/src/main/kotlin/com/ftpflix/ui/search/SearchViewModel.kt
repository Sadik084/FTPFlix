package com.ftpflix.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ftpflix.core.config.AgeRatingStore
import com.ftpflix.core.config.RatingWarmup
import com.ftpflix.data.model.CatalogEntry
import com.ftpflix.data.repository.MetadataRepository
import com.ftpflix.domain.parser.ParsedName
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration.Companion.milliseconds
import javax.inject.Inject

/** Type filter: All / Movies / Series */
enum class SearchFilter { All, Movies, Series }

/** Sort order for search results */
enum class SearchSort {
    Relevance,       // Default: similarity score (client-side)
    Popularity,      // TMDB popularity.desc
    Rating,          // TMDB vote_average.desc
    ReleaseDate,     // release_date.desc / first_air_date.desc
    Title,           // Title A-Z (client-side)
}

/** Complete search filter state */
data class SearchFilters(
    val type: SearchFilter = SearchFilter.All,
    val genres: Set<Int> = emptySet(),           // TMDB genre IDs
    val yearFrom: Int? = null,                   // Min year
    val yearTo: Int? = null,                     // Max year
    val minRating: Double = 0.0,                 // Minimum vote_average
    val languages: Set<String> = emptySet(),     // ISO 639-1 codes (en, es, etc.)
    val sortBy: SearchSort = SearchSort.Relevance,
    val certification: String? = null,           // e.g. "PG-13", "R", "TV-MA"
    val certificationCountry: String = "US",    // Certification country code
) {
    companion object {
        val DEFAULT = SearchFilters()
    }

    /** Whether any filter beyond the default type is active */
    val hasActiveFilters: Boolean
        get() = genres.isNotEmpty() || yearFrom != null || yearTo != null || minRating > 0 || languages.isNotEmpty() || sortBy != SearchSort.Relevance || certification != null
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val metadata: MetadataRepository,
    ageRatings: AgeRatingStore,
    private val warmup: RatingWarmup,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    /** Poster age-rating tags keyed by media id ("movie:123"); empty when unknown. */
    val ageRatings: StateFlow<Map<String, String>> = ageRatings.ratings

    private val _query = MutableStateFlow(savedState.get<String>(KEY_QUERY) ?: "")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filters = MutableStateFlow(
        savedState.get<String>(KEY_FILTERS)?.let { json ->
            try { Gson().fromJson(json, SearchFilters::class.java) } catch (_: Exception) { SearchFilters.DEFAULT }
        } ?: SearchFilters.DEFAULT
    )
    val filters: StateFlow<SearchFilters> = _filters.asStateFlow()

    private val _genres = MutableStateFlow<List<com.ftpflix.data.remote.TmdbGenre>>(emptyList())
    val genres: StateFlow<List<com.ftpflix.data.remote.TmdbGenre>> = _genres.asStateFlow()

    init {
        viewModelScope.launch { loadGenres() }
    }

    private suspend fun loadGenres() {
        val allGenres = metadata.getAllGenres()
        _genres.value = allGenres
    }

    fun getGenres(): List<com.ftpflix.ui.components.GenreItem> {
        return _genres.value.map { com.ftpflix.ui.components.GenreItem(it.id, it.name) }
    }

    /** True while a debounced search round-trip is in flight. */
    private val _searching = MutableStateFlow(value = false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    val results: StateFlow<List<CatalogEntry>> = combine(
        _query.debounce(300.milliseconds).distinctUntilChanged(),
        _filters.debounce(300.milliseconds).distinctUntilChanged(),
    ) { q, f -> q to f }
        .flatMapLatest { (q, filters) ->
            flow {
                if (q.trim().length < 2) {
                    emit(emptyList())
                } else {
                    _searching.value = true
                    try {
                        val list = search(q, filters)
                        if (list.isNotEmpty()) warmup.request(list.map { it.id })
                        emit(list)
                    } finally {
                        _searching.value = false
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        _query.value = value
        savedState[KEY_QUERY] = value
    }

    fun onFilterChange(value: SearchFilter) {
        _filters.value = _filters.value.copy(type = value)
        savedState[KEY_FILTERS] = Gson().toJson(_filters.value)
    }

    fun onFiltersChange(value: SearchFilters) {
        _filters.value = value
        savedState[KEY_FILTERS] = Gson().toJson(value)
    }

    fun clearFilters() {
        onFiltersChange(SearchFilters.DEFAULT)
    }

    // Individual filter change methods for ActiveFilterRow
    fun onGenreFilterChange(genreId: Int) {
        _filters.value = _filters.value.copy(genres = _filters.value.genres - genreId)
        savedState[KEY_FILTERS] = Gson().toJson(_filters.value)
    }

    fun onYearFilterChange(from: Int?, to: Int?) {
        _filters.value = _filters.value.copy(yearFrom = from, yearTo = to)
        savedState[KEY_FILTERS] = Gson().toJson(_filters.value)
    }

    fun onMinRatingChange(rating: Double) {
        _filters.value = _filters.value.copy(minRating = rating)
        savedState[KEY_FILTERS] = Gson().toJson(_filters.value)
    }

    fun onLanguageFilterChange(lang: String) {
        _filters.value = _filters.value.copy(languages = _filters.value.languages - lang)
        savedState[KEY_FILTERS] = Gson().toJson(_filters.value)
    }

    fun onSortChange(sort: SearchSort) {
        _filters.value = _filters.value.copy(sortBy = sort)
        savedState[KEY_FILTERS] = Gson().toJson(_filters.value)
    }

    fun onCertificationChange(certification: String?) {
        _filters.value = _filters.value.copy(certification = certification)
        savedState[KEY_FILTERS] = Gson().toJson(_filters.value)
    }

    fun onCertificationCountryChange(country: String) {
        _filters.value = _filters.value.copy(certificationCountry = country)
        savedState[KEY_FILTERS] = Gson().toJson(_filters.value)
    }

    private suspend fun search(q: String, filters: SearchFilters): List<CatalogEntry> {
        val (base, year) = splitTitleYear(q)
        val direct = candidatesFor(base, year, filters)
        if (direct.isNotEmpty()) return applyFilter(direct, filters)

        // Glued-query fallback: insert a space at each position (e.g. "breakingbad" -> "breaking bad").
        if ((!base.contains(' ')) && (base.length >= 6)) {
            val maxVariants = minOf(base.length - 1, 20)
            for (i in 1..maxVariants) {
                val variant = base.substring(0, i) + " " + base.substring(i)
                val found = candidatesFor(variant, year, filters)
                if (found.isNotEmpty()) return applyFilter(found, filters)
            }
        }

        // Typo-tolerant last resort: fuzzy-match the query against candidates.
        return applyFilter(fuzzyCandidates(base, year), filters)
    }

    // "Inception 2010" -> ("Inception", 2010).
    private fun splitTitleYear(query: String): Pair<String, Int?> {
        val yearRegex = Regex("\\b((?:19|20)\\d{2})\\b")
        val m = yearRegex.find(query) ?: return query.trim() to null
        val year = m.groupValues[1].toIntOrNull()
        val title = query.removeRange(m.range).trim()
        return title to year
    }

    // Fuzzy fallback: run the search once, rank by similarity, keep close matches.
    private suspend fun fuzzyCandidates(query: String, year: Int?): List<CatalogEntry> {
        if (query.length < 3) return emptyList()
        val all = candidatesFor(query, year, SearchFilters.DEFAULT)
        if (all.isEmpty()) return emptyList()
        return all.map { entry ->
            val score = com.ftpflix.util.TitleMatchingUtils
                .calculateSimilarityScore(query, entry.title)
            entry to score
        }
            .filter { it.second >= 0.5 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private suspend fun candidatesFor(query: String, year: Int?, filters: SearchFilters): List<CatalogEntry> {
        val parsed = ParsedName(
            title = query,
            year = year,
            season = null, episode = null,
            resolution = null, codec = null, isEpisode = false,
        )
        return metadata.candidatesFor(parsed, filters).map { candidate ->
            val kind = if (candidate.imdbId.startsWith("tv:")) "series" else "movie"
            CatalogEntry(
                id = "tmdb:${candidate.imdbId}",
                kind = kind,
                title = candidate.title,
                year = candidate.year,
                posterUrl = candidate.posterUrl,
                hasFile = false,
                genreIds = candidate.genreIds,
                voteAverage = candidate.voteAverage,
                originalLanguage = candidate.originalLanguage,
            )
        }
    }

    private fun applyFilter(candidates: List<CatalogEntry>, filters: SearchFilters): List<CatalogEntry> =
        candidates.asSequence()
            .filter { it.matches(filters) }
            .let { seq ->
                when (filters.sortBy) {
                    SearchSort.Relevance -> seq
                    SearchSort.Popularity -> seq.sortedByDescending { it.popularity ?: 0.0 }
                    SearchSort.Rating -> seq.sortedByDescending { it.voteAverage }
                    SearchSort.ReleaseDate -> seq.sortedByDescending { it.year ?: 0 }
                    SearchSort.Title -> seq.sortedBy { it.title.lowercase() }
                }
            }
            .take(60)
            .toList()

    private fun CatalogEntry.matches(filters: SearchFilters): Boolean {
        if (filters.type != SearchFilter.All && kind != filters.type.name.lowercase()) return false
        if (filters.yearFrom != null && (year == null || year < filters.yearFrom!!)) return false
        if (filters.yearTo != null && (year == null || year > filters.yearTo!!)) return false
        if (filters.genres.isNotEmpty()) {
            if (!filters.genres.all { genreId -> genreId in genreIds }) return false
        }
        if (filters.minRating > 0 && voteAverage < filters.minRating) return false
        if (filters.languages.isNotEmpty() && originalLanguage.isNotBlank()) {
            if (originalLanguage !in filters.languages) return false
        }
        return true
    }

    private companion object {
        private const val KEY_QUERY = "search_query"
        private const val KEY_FILTERS = "search_filters"
    }
}
