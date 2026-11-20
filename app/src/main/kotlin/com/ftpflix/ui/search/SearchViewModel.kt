package com.ftpflix.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ftpflix.core.config.AgeRatingStore
import com.ftpflix.core.config.RatingWarmup
import com.ftpflix.data.model.CatalogEntry
import com.ftpflix.data.repository.MetadataRepository
import com.ftpflix.data.repository.SearchUnavailableException
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
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
    val certification: Set<String> = emptySet(), // e.g. {"PG-13", "TV-MA"}
    val certificationCountry: String = "US",     // Certification country code
) {
    companion object {
        val DEFAULT = SearchFilters()
    }

    /** Whether any filter beyond the default type is active */
    val hasActiveFilters: Boolean
        get() = hasContentFilters || sortBy != SearchSort.Relevance

    /** Filters that narrow the candidate pool (need extra pages or server-side work). */
    val hasContentFilters: Boolean
        get() = genres.isNotEmpty() || yearFrom != null || yearTo != null ||
            minRating > 0 || languages.isNotEmpty() || certification.isNotEmpty()
}

/** Loading and error state of the current search. */
data class SearchStatus(
    val loading: Boolean = false,
    val error: String? = null,
)

/** Catalog kind ("movie"/"series") a type filter maps to; null means "all kinds". */
internal fun SearchFilter.catalogKind(): String? = when (this) {
    SearchFilter.All -> null
    SearchFilter.Movies -> "movie"
    SearchFilter.Series -> "series"
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

    /** Loading/error state of the current search round. */
    private val _status = MutableStateFlow(SearchStatus())
    val status: StateFlow<SearchStatus> = _status.asStateFlow()

    /** Bumped by [retry] to re-run the current search. */
    private val _retryTick = MutableStateFlow(0)

    val results: StateFlow<List<CatalogEntry>> = combine(
        _query.debounce(300.milliseconds).distinctUntilChanged(),
        _filters.debounce(300.milliseconds).distinctUntilChanged(),
        _retryTick,
    ) { q, f, _ -> q to f }
        .flatMapLatest { (q, filters) ->
            val trimmed = q.trim()
            if (trimmed.length < 2 && !filters.hasActiveFilters) {
                flowOf(emptyList<CatalogEntry>()).onStart { _status.value = SearchStatus() }
            } else {
                flow {
                    val list = search(trimmed, filters)
                    if (list.isNotEmpty()) warmup.request(list.map { it.id })
                    emit(list)
                }
                    .onStart { _status.value = SearchStatus(loading = true) }
                    .onCompletion { cause -> if (cause == null) _status.value = SearchStatus() }
                    .catch { error ->
                        _status.value = SearchStatus(error = errorMessage(error))
                        emit(emptyList())
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Re-runs the current search, e.g. after a failure. */
    fun retry() {
        _retryTick.value += 1
    }

    /** Human readable reason for a failed search round. */
    private fun errorMessage(error: Throwable): String = when (error) {
        is SearchUnavailableException -> "Could not reach TMDB. Check your connection and try again."
        is java.net.SocketTimeoutException -> "The request timed out. Try again."
        is java.net.UnknownHostException -> "No internet connection. Check your network and try again."
        else -> "Something went wrong while loading results."
    }

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

    /** Adds or removes a single age rating. */
    fun onCertificationToggle(certification: String) {
        val current = _filters.value.certification
        val next = if (certification in current) current - certification else current + certification
        _filters.value = _filters.value.copy(certification = next)
        savedState[KEY_FILTERS] = Gson().toJson(_filters.value)
    }

    /** Clears every selected age rating. */
    fun onCertificationClear() {
        _filters.value = _filters.value.copy(certification = emptySet())
        savedState[KEY_FILTERS] = Gson().toJson(_filters.value)
    }

    /** Switching country changes the available ratings, so the selection is reset. */
    fun onCertificationCountryChange(country: String) {
        _filters.value = _filters.value.copy(certificationCountry = country, certification = emptySet())
        savedState[KEY_FILTERS] = Gson().toJson(_filters.value)
    }

    private suspend fun search(q: String, filters: SearchFilters): List<CatalogEntry> {
        val query = q.trim()
        if (query.length < 2) {
            // No text query: browse the catalogue with the active filters.
            return applyFilter(discoverCandidates(filters), filters)
        }

        val (base, year) = splitTitleYear(query)
        val direct = candidatesFor(base, year, filters)
        val directFiltered = applyFilter(direct, filters)
        if (directFiltered.isNotEmpty()) return directFiltered

        // Glued-query fallback: insert a space at each position (e.g. "breakingbad" -> "breaking bad").
        val gluedFiltered = applyFilter(gluedCandidates(base, year, filters), filters)
        if (gluedFiltered.isNotEmpty()) return gluedFiltered

        // Typo-tolerant last resort: fuzzy-match the query against the candidates we already have.
        return applyFilter(fuzzyCandidates(direct, base), filters)
    }

    // "Inception 2010" -> ("Inception", 2010). A bare year stays a title ("2012").
    private fun splitTitleYear(query: String): Pair<String, Int?> {
        val yearRegex = Regex("\\b((?:19|20)\\d{2})\\b")
        val m = yearRegex.find(query) ?: return query.trim() to null
        val year = m.groupValues[1].toIntOrNull()
        val title = query.removeRange(m.range).trim()
        if (title.isBlank()) return query.trim() to null
        return title to year
    }

    // Glued-query fallback; bounded so a typo cannot stall the field for many seconds.
    private suspend fun gluedCandidates(query: String, year: Int?, filters: SearchFilters): List<CatalogEntry> {
        if (query.contains(' ') || query.length < 6) return emptyList()
        val maxVariants = minOf(query.length - 1, GLUED_VARIANT_LIMIT)
        for (i in 1..maxVariants) {
            val variant = query.substring(0, i) + " " + query.substring(i)
            val found = candidatesFor(variant, year, filters)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    // Fuzzy fallback: rank the candidates we already have by title similarity.
    private fun fuzzyCandidates(candidates: List<CatalogEntry>, query: String): List<CatalogEntry> {
        if (query.length < 3 || candidates.isEmpty()) return emptyList()
        return candidates.map { entry ->
            val score = com.ftpflix.util.TitleMatchingUtils
                .calculateSimilarityScore(query, entry.title)
            entry to score
        }
            .filter { it.second >= 0.5 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    // Browse by filters only; the repository routes this to TMDB's discover endpoint.
    private suspend fun discoverCandidates(filters: SearchFilters): List<CatalogEntry> =
        candidatesFor("", null, filters)

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
                popularity = candidate.popularity,
                releaseDate = candidate.releaseDate,
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
                    SearchSort.ReleaseDate -> seq.sortedByDescending { it.releaseDate ?: it.year?.let { y -> "$y-01-01" } ?: "" }
                    SearchSort.Title -> seq.sortedBy { it.title.lowercase() }
                }
            }
            .take(60)
            .toList()

    private fun CatalogEntry.matches(filters: SearchFilters): Boolean {
        val wantedKind = filters.type.catalogKind()
        if (wantedKind != null && kind != wantedKind) return false
        val from = filters.yearFrom
        val to = filters.yearTo
        if (from != null && (year == null || year < from)) return false
        if (to != null && (year == null || year > to)) return false
        if (filters.genres.isNotEmpty()) {
            if (!filters.genres.all { genreId -> genreId in genreIds }) return false
        }
        if (filters.minRating > 0 && voteAverage < filters.minRating) return false
        if (filters.languages.isNotEmpty() && originalLanguage !in filters.languages) return false
        return true
    }

    private companion object {
        private const val KEY_QUERY = "search_query"
        private const val KEY_FILTERS = "search_filters"
        private const val GLUED_VARIANT_LIMIT = 4
    }
}
