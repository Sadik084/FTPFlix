package com.ftpflix.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ftpflix.core.config.AgeRatingStore
import com.ftpflix.core.config.RatingWarmup
import com.ftpflix.data.model.CatalogEntry
import com.ftpflix.data.repository.MetadataRepository
import com.ftpflix.domain.parser.ParsedName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
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

enum class SearchFilter { All, Movies, Series }

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

    private val _filter = MutableStateFlow(
        savedState.get<String>(KEY_FILTER)?.let { name ->
            runCatching { SearchFilter.valueOf(name) }.getOrNull()
        } ?: SearchFilter.All,
    )
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()

    /** True while a debounced search round-trip is in flight. */
    private val _searching = MutableStateFlow(value = false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    val results: StateFlow<List<CatalogEntry>> = combine(
        _query.debounce(300.milliseconds).distinctUntilChanged(),
        _filter,
    ) { q, f -> q to f }
        .flatMapLatest { (q, filter) ->
            flow {
                if (q.trim().length < 2) {
                    emit(emptyList())
                } else {
                    _searching.value = true
                    try {
                        val list = search(q, filter)
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
        _filter.value = value
        savedState[KEY_FILTER] = value.name
    }

    private suspend fun search(q: String, filter: SearchFilter): List<CatalogEntry> {
        val (base, year) = splitTitleYear(q)
        val direct = candidatesFor(base, year)
        if (direct.isNotEmpty()) return applyFilter(direct, filter)

        // Glued-query fallback: insert a space at each position (e.g. "breakingbad" -> "breaking bad").
        if ((!base.contains(' ')) && (base.length >= 6)) {
            val maxVariants = minOf(base.length - 1, 20)
            for (i in 1..maxVariants) {
                val variant = base.substring(0, i) + " " + base.substring(i)
                val found = candidatesFor(variant, year)
                if (found.isNotEmpty()) return applyFilter(found, filter)
            }
        }

        // Typo-tolerant last resort: fuzzy-match the query against candidates.
        return applyFilter(fuzzyCandidates(base, year), filter)
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
        val all = candidatesFor(query, year)
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

    private suspend fun candidatesFor(query: String, year: Int?): List<CatalogEntry> {
        val parsed = ParsedName(
            title = query,
            year = year,
            season = null, episode = null,
            resolution = null, codec = null, isEpisode = false,
        )
        return metadata.candidatesFor(parsed).map { (imdbId, title, titleYear, posterUrl) ->
            val kind = if (imdbId.startsWith("tv:")) "series" else "movie"
            CatalogEntry(
                id = "tmdb:$imdbId",
                kind = kind,
                title = title,
                year = titleYear,
                posterUrl = posterUrl,
                hasFile = false,
            )
        }
    }

    private fun applyFilter(candidates: List<CatalogEntry>, filter: SearchFilter): List<CatalogEntry> =
        candidates.asSequence()
            .filter { it.matches(filter) }
            .take(60)
            .toList()

    private fun CatalogEntry.matches(filter: SearchFilter): Boolean =
        when (filter) {
            SearchFilter.All -> true
            SearchFilter.Movies -> kind == "movie"
            SearchFilter.Series -> kind == "series"
        }

    private companion object {
        private const val KEY_QUERY = "search_query"
        private const val KEY_FILTER = "search_filter"
    }
}
