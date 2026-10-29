package com.ftpflix.ui.details

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ftpflix.core.config.SourceSettingsStore
import com.ftpflix.core.util.fromRouteToken
import com.ftpflix.data.model.MovieMetadata
import com.ftpflix.data.repository.MetadataRepository
import com.ftpflix.data.repository.MyListRepository
import com.ftpflix.sources.hdhub.HdHubResolver
import com.ftpflix.sources.resolve.EpisodeStream
import com.ftpflix.sources.resolve.ResolvedStream
import com.ftpflix.sources.resolve.SeriesCatalog
import com.ftpflix.sources.resolve.SeriesResolver
import com.ftpflix.sources.resolve.SeriesSeason
import com.ftpflix.sources.resolve.StreamResolver
import com.ftpflix.sources.resolve.StreamSource
import com.ftpflix.ui.navigation.DetailsRoute
import com.ftpflix.ui.player.PlaybackSession
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Result of resolving a single stream source for the current movie. */
sealed interface SourceResult {
    data object Loading : SourceResult
    data class Ready(val stream: ResolvedStream) : SourceResult
    /** Source reachable / present but yielded no playable stream. */
    data object Empty : SourceResult
    data class Failed(val message: String) : SourceResult
}

@Immutable
data class DetailsUi(
    val contentKey: String = "",
    val metadata: MovieMetadata? = null,
    val isLoading: Boolean = true,
    val inMyList: Boolean = false,
    val resolvingStream: Boolean = false,
    val stream: ResolvedStream? = null,
    val streamError: String? = null,
    val series: SeriesCatalog? = null,
    val loadingSeries: Boolean = false,
    val seriesError: String? = null,
    val selectedSeason: Int? = null,
    /** season -> (episodeNumber -> TMDB name) for real episode titles. */
    val episodeNames: Map<Int, Map<Int, String>> = emptyMap(),
    /** Pinned stream source; AUTO uses the user-configured order from Settings. */
    val selectedSource: StreamSource = StreamSource.AUTO,
    /** HdHub quality tier (null = best available). Only consulted when [selectedSource] is HdHub. */
    val hdhubHeight: Int? = null,
    /** Quality tiers the HdHub addon currently offers for this title (best first). */
    val hdhubQualities: List<Int> = emptyList(),
    val loadingHdhubQualities: Boolean = false,
    /** Source chips to show: AUTO + user-enabled sources in the user order. */
    val availableSources: List<StreamSource> = listOf(StreamSource.AUTO),
    /** Per-source resolution outcomes (movies), so tabs reuse and show status. */
    val sourceResults: Map<StreamSource, SourceResult> = emptyMap(),
    /** True while an episode URL is being resolved from the pinned source before playback. */
    val resolvingEpisode: Boolean = false,
    /** Resolved playback request awaiting hand-off to navigation (series source picks). */
    val pendingPlay: PlayRequest? = null,
    /** Per-episode resolution failure for the pinned source (e.g. HdHub miss). */
    val episodeError: String? = null,
) {
    val isSeries: Boolean get() = metadata?.kind == "series"
}

@HiltViewModel
class DetailsViewModel @Inject constructor(
    private val metadataRepo: MetadataRepository,
    private val myList: MyListRepository,
    private val streamResolver: StreamResolver,
    private val seriesResolver: SeriesResolver,
    private val hdhub: HdHubResolver,
    private val sourceSettings: SourceSettingsStore,
    private val playbackSession: PlaybackSession,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val key: String = runCatching {
        savedState.toRoute<DetailsRoute>().key.fromRouteToken()
    }.getOrDefault("")
    private val mediaId: String = if (key.startsWith("tmdb:")) key.removePrefix("tmdb:") else ""

    private val _ui = MutableStateFlow(DetailsUi())
    val ui: StateFlow<DetailsUi> = _ui.asStateFlow()

    // Session-scoped results/catalogs so re-clicks reuse fetches.
    private val sessionMovieResults = mutableMapOf<StreamSource, SourceResult>()
    private val sessionCatalogs = mutableMapOf<StreamSource, SeriesCatalog?>()
    private val sessionHdhubQualities = mutableMapOf<String, List<Int>>()

    private var cachedOrder: List<StreamSource> = SourceSettingsStore.DEFAULT_ORDER
    private var cachedEnabled: Set<StreamSource> = setOf(
        StreamSource.CIRCLE_FTP, StreamSource.ROAR_ZONE, StreamSource.HD_HUB,
    )

    init {
        Log.d("DetailsViewModel", "init with key='$key', mediaId='$mediaId'")
        viewModelScope.launch {
            cachedOrder = sourceSettings.sourceOrder.first()
            cachedEnabled = sourceSettings.enabledSources.first()
            _ui.update { state ->
                state.copy(
                    availableSources = listOf(StreamSource.AUTO) + cachedOrder.filter { it in cachedEnabled },
                )
            }
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            if (mediaId.isBlank()) {
                Log.w("DetailsViewModel", "mediaId is blank for key='$key'")
                _ui.update { it.copy(isLoading = false) }
                return@launch
            }
            val kind = mediaId.substringBefore(':')
            val id = mediaId.substringAfter(':', "")
            Log.d("DetailsViewModel", "Fetching metadata for kind='$kind', id='$id'")
            val meta = if (id.isNotBlank()) metadataRepo.details("$kind:$id") else null
            Log.d("DetailsViewModel", "Fetched metadata: title='${meta?.title}', year=${meta?.year}, kind='${meta?.kind}'")
            _ui.update {
                it.copy(
                    contentKey = key,
                    metadata = meta,
                    isLoading = false,
                    inMyList = myList.contains(key),
                )
            }
            val title = meta?.title
            if (title != null) {
                if (meta.kind == "series") {
                    ensureSeriesCatalog(StreamSource.AUTO)
                } else {
                    // Stale series data must not leak into a movie playback.
                    playbackSession.clear()
                    ensureMovieResolved(StreamSource.AUTO)
                }
            }
        }
    }

    // ---------------------------------------------------------------- movies

    /** Resolve [source] for the current movie, reusing any stored result (first-time only). */
    private fun ensureMovieResolved(source: StreamSource) {
        val (_, title, year, _, _, _, _, _, _, _, _, _, _, _) = _ui.value.metadata ?: return
        if (source == StreamSource.AUTO) {
            viewModelScope.launch { runAutoMovie(title, year) }
            return
        }
        if (source !in cachedEnabled) {
            // Disabled in Settings: behave as AUTO for this screen session.
            viewModelScope.launch { runAutoMovie(title, year) }
            return
        }
        if (sessionMovieResults.containsKey(source)) {
            publishMovieState()
            return
        }
        viewModelScope.launch {
            sessionMovieResults[source] = SourceResult.Loading
            publishMovieState()
            sessionMovieResults[source] = resolveOne(source, title, year)
            publishMovieState()
        }
    }

    private suspend fun runAutoMovie(title: String, year: Int?) {
        for (s in cachedOrder) {
            if (s !in cachedEnabled) continue
            if (sessionMovieResults.containsKey(s)) continue
            sessionMovieResults[s] = SourceResult.Loading
            publishMovieState()
            sessionMovieResults[s] = resolveOne(s, title, year)
            publishMovieState()
            if (sessionMovieResults[s] is SourceResult.Ready) break
        }
    }

    private suspend fun resolveOne(source: StreamSource, title: String, year: Int?): SourceResult {
        return try {
            val height = if (source == StreamSource.HD_HUB) _ui.value.hdhubHeight else null
            val stream = streamResolver.resolve(
                title, year,
                imdbId = _ui.value.metadata?.imdbId,
                force = false,
                source = source,
                preferredHeight = height,
            )
            if (stream == null) SourceResult.Empty else SourceResult.Ready(stream)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("DetailsViewModel", "resolve failed for ${source.name}", e)
            SourceResult.Failed(e.message ?: "Resolution failed")
        }
    }

    // Recompute stream/error/resolvingStream from session results.
    private fun publishMovieState() {
        val selected = _ui.value.selectedSource
        val anyLoading = sessionMovieResults.values.any { it is SourceResult.Loading }
        val (stream, error) = if (selected == StreamSource.AUTO) {
            val ready = cachedOrder.asSequence()
                .mapNotNull { sessionMovieResults[it] }
                .filterIsInstance<SourceResult.Ready>()
                .firstOrNull()
            val attemptedAll = cachedOrder.asSequence()
                .filter { it in cachedEnabled }
                .all { sessionMovieResults.containsKey(it) }
            val failedAll = attemptedAll && (ready == null)
            ready?.stream to if (failedAll) "No playable stream found from any source." else null
        } else {
            when (val r = sessionMovieResults[selected]) {
                is SourceResult.Ready -> r.stream to null
                SourceResult.Empty -> null to "No playable stream found from ${selected.label}."
                is SourceResult.Failed -> null to r.message
                else -> null to null // Loading or not attempted yet keeps previous values
            }
        }
        _ui.update {
            it.copy(
                resolvingStream = anyLoading,
                stream = stream,
                streamError = error,
            )
        }
    }

    // Pin a source; movies resolve lazily on first visit.
    fun selectSource(source: StreamSource) {
        if (_ui.value.selectedSource == source) return
        _ui.update { it.copy(selectedSource = source, streamError = null, episodeError = null) }
        if (_ui.value.isSeries) {
            if (source == StreamSource.HD_HUB) {
                // HdHub has no browse endpoint: keep the current list, resolve per episode.
                if (_ui.value.series == null) ensureSeriesCatalog(StreamSource.AUTO)
            } else {
                ensureSeriesCatalog(source)
            }
        } else {
            ensureMovieResolved(source)
        }
        if (source == StreamSource.HD_HUB) fetchHdhubQualities()
    }

    // Pin an HdHub quality tier; only HdHub re-resolves.
    fun selectHdHubQuality(height: Int?) {
        if (_ui.value.hdhubHeight == height) return
        _ui.update { it.copy(hdhubHeight = height, streamError = null) }
        sessionMovieResults.remove(StreamSource.HD_HUB)
        val selected = _ui.value.selectedSource
        if ((!_ui.value.isSeries) && ((selected == StreamSource.HD_HUB) || (selected == StreamSource.AUTO))) {
            ensureMovieResolved(selected)
        }
    }

    fun resolveStream() {
        val title = _ui.value.metadata?.title
        if (title == null) {
            Log.w("DetailsViewModel", "resolveStream() called but metadata title is null!")
            return
        }
        ensureMovieResolved(_ui.value.selectedSource)
    }

    // ---------------------------------------------------------------- series

    // Load/reuse the catalog for the given source.
    private fun ensureSeriesCatalog(source: StreamSource) {
        if (_ui.value.loadingSeries) return
        val useSource = if ((source in cachedEnabled) || (source == StreamSource.AUTO)) source else StreamSource.AUTO

        val cached = sessionCatalogs[useSource]
        if (cached != null) {
            applyCatalog(cached)
            return
        }
        val (_, metaTitle, metaYear, _, _, _, _, _, _, _, _, metaImdbId, _, metaSeasons) =
            _ui.value.metadata ?: return
        viewModelScope.launch {
            _ui.update { it.copy(loadingSeries = true, seriesError = null) }
            val catalog = try {
                if (useSource == StreamSource.AUTO) {
                    seriesResolver.resolveSeries(
                        metaTitle, metaYear,
                        imdbId = metaImdbId,
                        seasonCount = metaSeasons,
                    )
                } else {
                    seriesResolver.resolveSeriesPinned(
                        useSource,
                        metaTitle, metaYear,
                        imdbId = metaImdbId,
                        seasonCount = metaSeasons,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DetailsViewModel", "seriesResolver failed for ${useSource.name}", e)
                null
            }
            sessionCatalogs[useSource] = catalog
            catalog?.let { populateSession(metaTitle, it) }
            _ui.update {
                val firstSeason = catalog?.sortedSeasons()?.firstOrNull()?.number
                it.copy(
                    loadingSeries = false,
                    series = catalog,
                    seriesError = if (catalog == null) "No episodes found for this source." else null,
                    selectedSeason = it.selectedSeason ?: firstSeason,
                )
            }
        }
    }

    private fun applyCatalog(catalog: SeriesCatalog?) {
        _ui.update {
            val firstSeason = catalog?.sortedSeasons()?.firstOrNull()?.number
            it.copy(
                series = catalog,
                seriesError = if (catalog == null) "No episodes found for this source." else null,
                selectedSeason = it.selectedSeason ?: firstSeason
            )
        }
    }

    // Feed the player's episode browser with flat episodes + names.
    private suspend fun populateSession(title: String, catalog: SeriesCatalog) {
        val flat = catalog.sortedSeasons().flatMap(SeriesSeason::episodes)
        playbackSession.set(
            seriesTitle = title,
            contentKey = key,
            posterUrl = _ui.value.metadata?.posterUrl,
            episodes = flat,
            episodeNames = emptyMap(),
            currentIndex = -1
        )
        val tmdbId = mediaId.substringAfter("tv:", "").toLongOrNull()
        if ((tmdbId == null) || flat.isEmpty()) return
        val names = try {
            metadataRepo.seasonEpisodeNames(tmdbId, catalog.sortedSeasons().map(SeriesSeason::number))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("DetailsViewModel", "seasonEpisodeNames failed", e)
            emptyMap()
        }
        playbackSession.episodeNames = names
        _ui.update { it.copy(episodeNames = names) }
    }

    /** Called right before navigating to the player so Next-up knows the index. */
    fun prepareEpisodePlayback(episode: EpisodeStream?) {
        val flat = playbackSession.episodes
        if (flat.isEmpty()) return
        playbackSession.currentIndex =
            if (episode == null) -1 else flat.indexOfFirst { it.matchesUrl(episode.url) }
    }

    private fun EpisodeStream.matchesUrl(url: String): Boolean = this.url == url

    fun selectSeason(season: Int) {
        _ui.update { it.copy(selectedSeason = season) }
        if ((_ui.value.selectedSource == StreamSource.HD_HUB) && _ui.value.isSeries) {
            fetchHdhubQualities()
        }
    }

    // Load quality tiers HdHub currently offers for this title.
    private fun fetchHdhubQualities() {
        val ui = _ui.value
        val imdb = ui.metadata?.imdbId ?: return
        val cacheKey = if (ui.isSeries) "s${ui.selectedSeason ?: 1}" else "movie"
        sessionHdhubQualities[cacheKey]?.let {
            _ui.update { s -> s.copy(hdhubQualities = it, loadingHdhubQualities = false) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(loadingHdhubQualities = true) }
            val heights = try {
                if (ui.isSeries) {
                    val season = ui.selectedSeason ?: 1
                    val episode = ui.series?.season(season)?.firstOrNull()?.episode ?: 1
                    hdhub.availableHeights("series", "$imdb:$season:$episode")
                } else {
                    hdhub.availableHeights("movie", imdb)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "hdhub quality fetch failed", e)
                emptyList()
            }
            sessionHdhubQualities[cacheKey] = heights
            _ui.update { it.copy(hdhubQualities = heights, loadingHdhubQualities = false) }
        }
    }

    fun retrySeries() {
        // Retry the source the user is on; HdHub falls back to the catalog source.
        val selected = _ui.value.selectedSource
        val source = if (selected == StreamSource.HD_HUB) StreamSource.AUTO else selected
        sessionCatalogs.remove(source)
        _ui.update { it.copy(seriesError = null) }
        ensureSeriesCatalog(source)
    }

    // Series episode tap: catalog URL for AUTO/Circle/Roar, on-demand resolve for HdHub.
    fun requestEpisodePlayback(episode: EpisodeStream) {
        prepareEpisodePlayback(episode)
        val (_, uiMetadata, _, _, _, _, _, _, _, _, _, _, uiSelectedSource, uiHdhubHeight, _, _, _, _) =
            _ui.value
        when (uiSelectedSource) {
            StreamSource.AUTO, StreamSource.CIRCLE_FTP, StreamSource.ROAR_ZONE -> {
                _ui.update { it.copy(pendingPlay = playRequestFor(episode, episode.url), episodeError = null) }
            }
            StreamSource.HD_HUB -> {
                viewModelScope.launch {
                    _ui.update { it.copy(resolvingEpisode = true, episodeError = null) }
                    val resolved = hdhub.resolveEpisode(
                        uiMetadata?.imdbId,
                        episode.season ?: 1,
                        episode.episode ?: 1,
                        uiHdhubHeight,
                    )
                    if (resolved != null) {
                        _ui.update { it.copy(resolvingEpisode = false, pendingPlay = playRequestFor(episode, resolved.url)) }
                    } else {
                        _ui.update { it.copy(resolvingEpisode = false, episodeError = "HdHub has no playable stream for this episode.") }
                    }
                }
            }
        }
    }

    fun consumePendingPlay() {
        _ui.update { it.copy(pendingPlay = null) }
    }

    private companion object {
        private const val TAG = "DetailsViewModel"
    }

    private fun playRequestFor(episode: EpisodeStream, url: String): PlayRequest {
        val (uiContentKey, uiMetadata, _, _, _, _, _, _, _, _, _, uiEpisodeNames, _, _, _, _, _, _) = _ui.value
        val name = uiEpisodeNames[episode.season]?.get(episode.episode)
            ?: "Episode ${episode.episode ?: ""}"
        val code = "S" + (episode.season ?: 0).toString().padStart(2, '0') +
            "E" + (episode.episode ?: 0).toString().padStart(2, '0')
        val episodeTitle = "$code  •  $name"
        return PlayRequest(
            mediaKey = url,
            contentKey = uiContentKey,
            title = uiMetadata?.title ?: name,
            posterUrl = uiMetadata?.posterUrl,
            episodeLabel = episodeTitle,
            year = uiMetadata?.year,
        )
    }

    fun toggleMyList() {
        viewModelScope.launch {
            val (_, uiMetadata, _, uiInMyList, _, _, _, _, _, _, _, _, _, _, _, _, _, _) = _ui.value
            val (_, metaTitle, metaYear, metaPosterUrl, _, _, _, _, _, _, metaKind, _, _, _) =
                uiMetadata ?: return@launch
            if (uiInMyList) {
                myList.remove(key)
                _ui.update { it.copy(inMyList = false) }
            } else {
                myList.add(
                    key = key,
                    kind = metaKind,
                    title = metaTitle,
                    year = metaYear,
                    posterUrl = metaPosterUrl,
                )
                _ui.update { it.copy(inMyList = true) }
            }
        }
    }
}
