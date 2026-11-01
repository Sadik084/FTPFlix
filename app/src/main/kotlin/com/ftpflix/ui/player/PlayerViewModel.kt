package com.ftpflix.ui.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.ftpflix.core.config.FitMode
import com.ftpflix.core.config.PlaybackSettings
import com.ftpflix.core.util.StreamMeta
import com.ftpflix.core.util.fromRouteToken
import com.ftpflix.data.model.MovieMetadata
import com.ftpflix.data.repository.HistoryRepository
import com.ftpflix.data.repository.MetadataRepository
import com.ftpflix.sources.HttpProbe
import com.ftpflix.sources.resolve.EpisodeStream
import com.ftpflix.sources.resolve.SeriesResolver
import com.ftpflix.sources.resolve.StreamResolver
import com.ftpflix.sources.resolve.withTmdbSeasons
import com.ftpflix.ui.components.isUpcoming
import kotlinx.coroutines.CancellationException
import com.ftpflix.ui.navigation.PlayerRoute
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import javax.inject.Inject

@Immutable
data class PlayerUi(
    val buffering: Boolean = true,
    val isPlaying: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val speed: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** Sample aspect ratio from the container (anamorphic MKVs are ≠1); display width = width * this. */
    val videoPixelAspect: Float = 1f,
    val fitMode: FitMode = FitMode.Fit,
    val locked: Boolean = false,
    val title: String = "",
    /** Server host of the playing stream ("stats for nerds"). */
    val serverHost: String? = null,
    /** File size in bytes, when known ("stats for nerds"). */
    val fileSizeBytes: Long = 0L,
)

/** High-frequency playback position, split from PlayerUi to avoid full-screen recompositions. */
@Immutable
data class Progress(val positionMs: Long = 0L, val durationMs: Long = 0L)

/** Live + static stream stats for the "stats for nerds" Info panel. */
@Immutable
data class PlayerStats(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    /** Current video bitrate in kbps. */
    val videoBitrateKbps: Long = 0L,
    val videoCodec: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val audioCodec: String? = null,
    val audioChannels: Int = 0,
    val containerMime: String? = null,
    val isBuffering: Boolean = false,
    /** Recent bitrate samples (kbps) for the sparkline graph. */
    val bitrateHistory: List<Long> = emptyList(),
)



// Plays a direct media URL with software decoders for AC-3/E-AC-3 audio.
@UnstableApi
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val history: HistoryRepository,
    private val streamResolver: StreamResolver,
    private val seriesResolver: SeriesResolver,
    private val metadataRepo: MetadataRepository,
    private val httpProbe: HttpProbe,
    private val playbackSession: PlaybackSession,
    private val playbackSettings: PlaybackSettings,
    savedState: SavedStateHandle,
) : ViewModel() {

    // Outlives viewModelScope so the final progress write in onCleared lands.
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val renderersFactory = DefaultRenderersFactory(ctx).apply {
        // FFmpeg software decoders first, hardware fallback; MediaCodec alone can't decode AC-3/E-AC-3 on most devices.
        setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        // Let ExoPlayer retry the software decoder when a hardware one fails.
        setEnableDecoderFallback(true)
    }

    // Long read timeout: large direct streams can delay before the first byte.
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(READ_TIMEOUT_MS)
        .setAllowCrossProtocolRedirects(true)

    private val dataSourceFactory = DefaultDataSource.Factory(ctx, httpDataSourceFactory)

    private val json = Json { ignoreUnknownKeys = true }

    val player: ExoPlayer = ExoPlayer.Builder(ctx, renderersFactory)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(50_000, 250_000, 2_500, 5_000)
                .build(),
        )
        .build()

    init {
        // Must be applied before prepare(): a disabled audio renderer means video-only playback (silence + empty audio list).
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
        // Fit modes size the surface; the renderer just fills it (V3).
        player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT

        // The bandwidth meter feeds the stats graph's bitrate sparkline.
        player.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onBandwidthEstimate(
                    eventTime: AnalyticsListener.EventTime,
                    totalLoadTimeMs: Int,
                    totalBytesLoaded: Long,
                    bitrateEstimate: Long,
                ) {
                    latestBandwidthBps = bitrateEstimate
                }
            }
        )
    }

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val _episodeIndex = MutableStateFlow(-1)
    val currentEpisodeIndex: StateFlow<Int> = _episodeIndex.asStateFlow()

    private val _showNextUp = MutableStateFlow(value = false)
    val showNextUp: StateFlow<Boolean> = _showNextUp.asStateFlow()

    private val _stats = MutableStateFlow(PlayerStats())
    val stats: StateFlow<PlayerStats> = _stats.asStateFlow()

    val hasEpisodes: Boolean get() = playbackSession.hasEpisodes
    val playbackEpisodes: List<EpisodeStream> get() = playbackSession.episodes

    private var progressLoop: Job? = null
    private var controlsVisible = true
    private var mediaKey: String? = null
    private var contentKey: String = ""
    private var displayTitle: String = ""
    private var displayPosterUrl: String? = null
    private var displayEpisodeLabel: String = ""
    private var displayYear: Int? = null
    private var streamHeaders: Map<String, String> = emptyMap()
    private var retried = false
    private val rejectedUrls = mutableSetOf<String>()
    private var altAttempts = 0
    @Volatile private var altInFlight = false
    @Volatile private var nextUpHidden = false

    // Bitrate sampling state ("stats for nerds").
    /** Bitrate declared by the container (Format.bitrate); fallback until a live estimate exists. */
    @Volatile private var declaredKbps = 0L
    /** Shown in the Info panel/graph: measured transfer bitrate, falling back to [declaredKbps]. */
    @Volatile private var currentKbps = 0L
    /** Latest bandwidth estimate from ExoPlayer's network stack (bits/sec), fed by [AnalyticsListener]. */
    @Volatile private var latestBandwidthBps = 0L
    private val bitrateHistory = ArrayDeque<Long>()
    private var videoCodec: String? = null
    private var audioCodec: String? = null
    private var audioChannels = 0
    private var containerMime: String? = null

    // Source of truth for position; ExoPlayer can read 0 during a failed seek/stall.
    @Volatile private var lastKnownPositionMs = 0L

    /** The larger of the player's live position and the last known good position. */
    private fun bestKnownPositionMs(): Long = maxOf(player.currentPosition, lastKnownPositionMs)

    init {
        // Let ExoPlayer match the content frame rate on VRR panels.
        player.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        val route = runCatching {
            savedState.toRoute<PlayerRoute>()
        }.getOrNull()
        mediaKey = route?.mediaKey?.fromRouteToken().orEmpty().ifBlank { "" }
        contentKey = route?.contentKey?.fromRouteToken().orEmpty()
        displayTitle = route?.title.orEmpty()
        displayPosterUrl = route?.posterUrl?.takeIf { it.isNotBlank() }
        displayEpisodeLabel = route?.episodeLabel.orEmpty()
        displayYear = route?.year?.takeIf { it > 0 }
        streamHeaders = runCatching { json.decodeFromString<Map<String, String>>(route?.headers.orEmpty()) }
            .getOrDefault(emptyMap())
        _episodeIndex.value = playbackSession.currentIndex
        _ui.update {
            it.copy(title = initialTitle())
        }
        viewModelScope.launch {
            val speed = playbackSettings.playbackSpeed.first()
            player.setPlaybackSpeed(speed)
            // Restore the persisted fit mode instead of resetting to Fit on every open (V3).
            val fitMode = playbackSettings.fitMode.first()
            _ui.update { it.copy(speed = speed, fitMode = fitMode) }
            val key = mediaKey ?: ""
            if (key.isBlank()) {
                _ui.update { it.copy(error = "This item does not have a valid direct media URL.") }
            } else {
                open(key)
            }
        }

        player.addListener(
            object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) retried = false
                _ui.update {
                    it.copy(buffering = (state == Player.STATE_BUFFERING || state == Player.STATE_IDLE))
                }
                // A failed seek/stall can read 0; never let it snap the UI or a save back to the start.
                val position = bestKnownPositionMs()
                if (position > 0) lastKnownPositionMs = position
                _progress.value = Progress(position, player.duration.coerceAtLeast(0L))
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _ui.update { it.copy(isPlaying = isPlaying) }
                // Poll while playing regardless of controls visibility: Next-Up must trip during the outro, when controls are hidden.
                if (isPlaying) startPositionUpdates()
                if (!isPlaying) saveProgressNow()
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                // Guard VideoSize.UNKNOWN (0x0, e.g. audio-only streams): keep the last known size (V3).
                if (videoSize == androidx.media3.common.VideoSize.UNKNOWN) return
                _ui.update {
                    it.copy(
                        videoWidth = videoSize.width,
                        videoHeight = videoSize.height,
                        videoPixelAspect = videoSize.pixelWidthHeightRatio,
                    )
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                captureTracks()
            }

            override fun onPlayerError(error: PlaybackException) {
                saveProgressNow()
                // Log the failure type so playable-but-undecodable streams are easy to spot.
                val isDecoderError = error.errorCode in DECODER_ERRORS
                Log.e(
                    TAG,
                    "Playback error code=${error.errorCode} decoderError=$isDecoderError url=${mediaKey ?: "?"} msg=${error.message}",
                    error
                )
                if (isDecoderError) {
                    tryAlternateSource(bestKnownPositionMs())
                    return
                }
                if (!retried) {
                    retried = true
                    // Retry from the last known position, never from 0.
                    val pos = bestKnownPositionMs()
                    viewModelScope.launch {
                        // Probe the current URL first; transient errors re-prepare the same item, never re-resolve.
                        val urlStillAlive = mediaKey?.let { httpProbe.headOk(it, streamHeaders) } == true
                        if (urlStillAlive) {
                            retrySameItem(pos)
                            return@launch
                        }
                        // URL is dead: re-resolve once, with the year so the resolver's year gate stays engaged.
                        val title = displayTitle.ifBlank {
                            displayEpisodeLabel.ifBlank { "" }
                        }
                        val fresh = if (title.isBlank()) null else try {
                            streamResolver.resolve(
                                title, displayYear,
                                imdbId = resolveMetadata()?.imdbId, force = true
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        when {
                            // Accept a different URL only when it is the same movie.
                            fresh != null && fresh.url != mediaKey &&
                                StreamMeta.sameMovieTitle(mediaKey, fresh.url) -> {
                                mediaKey = fresh.url
                                retried = false
                                playUrl(fresh.url, pos)
                            }
                            // Same URL again: try another source.
                            fresh != null && fresh.url == mediaKey ->
                                tryAlternateSource(pos, notice = SOURCE_RETRY_NOTICE)
                            fresh != null -> retrySameItem(pos)
                            // Last resort: re-prepare the current item at the last known position.
                            pos > 0 -> retrySameItem(pos)
                            else -> _ui.update {
                                it.copy(error = "Could not play this file. Please try again.")
                            }
                        }
                    }
                    return
                }
                _ui.update {
                    it.copy(error = "Could not play this file. Please try again.")
                }
            }
        })

        startProgressSaving()
        startStatsSampling()

        // Netflix-style next-up pill near the end of an episode; hide on seek-back or dismissal.
        viewModelScope.launch {
            _progress.collect { (position, duration) ->
                val inWindow = duration > 0 && position > 0 &&
                    (duration - position) in 1..NEXT_UP_WINDOW_MS
                if (!inWindow) nextUpHidden = false
                val show = inWindow && !nextUpHidden &&
                    playbackSession.next() != null && ui.value.error == null
                if (show != _showNextUp.value) _showNextUp.value = show
            }
        }


    }

    private fun open(key: String) {
        val uri = runCatching { key.toUri() }.getOrNull()
        if (uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty()) {
            startPlayback(key, uri)
            return
        }
        _ui.update { it.copy(error = "This item does not have a valid direct media URL.") }
    }

    private fun startPlayback(key: String, url: Uri) {
        resetVideoSize()
        lastKnownPositionMs = 0
        rejectedUrls.clear()
        altAttempts = 0
        altInFlight = false
        applyRequestHeaders()
        _ui.update { it.copy(
            serverHost = url.host?.takeIf { h -> h.isNotBlank() },
            fileSizeBytes = 0L
        ) }
        player.setMediaItem(MediaItem.fromUri(url))
        viewModelScope.launch {
            // Best-effort file size for the Info panel (never blocks playback).
            val size = runCatching { httpProbe.contentLength(key, streamHeaders) }.getOrNull()
            _ui.update { it.copy(fileSizeBytes = size ?: 0L) }
            val content = contentKey.ifBlank { key }
            history.get(content)?.takeIf { it.streamUrl == key }?.let { saved ->
                // Finished items restart from the beginning (rewatch); partially watched ones resume where they stopped.
                if (!saved.isFinished && saved.positionMs in 1 until saved.durationMs) {
                    player.seekTo(saved.positionMs)
                    lastKnownPositionMs = saved.positionMs
                }
            }
            player.prepare()
            player.playWhenReady = true
            rebuildSeriesSessionIfNeeded()
        }
    }

    // Clear the previous item's video size so the surface doesn't flash its aspect.
    private fun resetVideoSize() {
        _ui.update { it.copy(videoWidth = 0, videoHeight = 0, videoPixelAspect = 1f) }
    }

    // Details for the content key (Room-cached); supplies IMDb id + season count to resolvers.
    private suspend fun resolveMetadata(): MovieMetadata? =
        runCatching { metadataRepo.details(contentKey.removePrefix("tmdb:")) }.getOrNull()

    // Rebuild the session when an episode launches straight from Continue Watching (session is memory-only).
    private suspend fun rebuildSeriesSessionIfNeeded() {
        if (playbackSession.hasEpisodes || displayEpisodeLabel.isBlank()) return
        val title = displayTitle.ifBlank { return }
        val meta = resolveMetadata()
        val catalog = try {
            seriesResolver.resolveSeries(
                title, displayYear,
                imdbId = meta?.imdbId, seasonCount = meta?.numberOfSeasons
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        // TMDB seasons the sources don't list yet.
        val counts = try {
            metadataRepo.seasonEpisodeCounts(contentKey.removePrefix("tmdb:"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
        val merged = catalog.withTmdbSeasons(counts)
        val flat = merged.sortedSeasons().flatMap { it.episodes }
        if (flat.isEmpty()) return
        // Match by url, then by the SxxEyy in the label, then season 1.
        val (labelSeason, labelEpisode) = parseEpisodeLabel(displayEpisodeLabel)
        val index = flat.indexOfFirst { it.url == mediaKey }
            .takeIf { it >= 0 }
            ?: flat.indexOfFirst { it.season == labelSeason && it.episode == labelEpisode }
                .takeIf { it >= 0 }
            ?: flat.indexOfFirst { it.season == 1 } // best effort
        val tmdbId = contentKey.removePrefix("tmdb:").removePrefix("tv:").toLongOrNull()
        val epMeta = if (tmdbId != null) {
            try {
                metadataRepo.seasonEpisodeMeta(tmdbId, merged.sortedSeasons().map { it.number })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap()
            }
        } else emptyMap()
        val names = epMeta.mapValues { (_, eps) ->
            eps.mapNotNull { (num, m) -> m.name?.let { num to it } }.toMap()
        }
        val dates = epMeta.mapValues { (_, eps) ->
            eps.mapNotNull { (num, m) -> m.airDate?.let { num to it } }.toMap()
        }
        playbackSession.set(
            seriesTitle = title,
            contentKey = contentKey,
            posterUrl = displayPosterUrl,
            episodes = flat,
            episodeNames = names,
            episodeDates = dates,
            currentIndex = index.coerceAtLeast(0)
        )
        _episodeIndex.value = index.coerceAtLeast(0)
    }

    // "S03E01 · Name" -> (3, 1); (-1, -1) when absent.
    private fun parseEpisodeLabel(label: String): Pair<Int, Int> {
        val m = Regex("(?i)\\bS(\\d{1,2})E(\\d{1,3})\\b").find(label) ?: return -1 to -1
        return (m.groupValues[1].toIntOrNull() ?: -1) to (m.groupValues[2].toIntOrNull() ?: -1)
    }

    private fun startProgressSaving() {
        viewModelScope.launch {
            while (isActive) {
                delay(10.seconds)
                if (player.isPlaying) saveProgressNow()
            }
        }
    }

    /** Persists the current position; called on pause, background stop and exit. */
    fun saveProgressNow() {
        val key = mediaKey ?: return
        // ExoPlayer must only be touched on the main thread: capture values here, let the IO scope touch Room only (crash fix).
        val position = bestKnownPositionMs()
        val duration = player.duration.coerceAtLeast(0L)
        // Save even at position 0 so a buffering exit still lands in history; skip only when nothing loaded.
        if (position <= 0L && duration <= 0L) return
        // Title and episode label are stored separately; the title is what series re-resolution needs on Continue-Watching resume.
        val title = displayTitle.ifBlank {
            runCatching { Uri.decode(key.toUri().lastPathSegment ?: "") }
                .getOrDefault("")
                .ifBlank { "Video" }
        }
        val epLabel = currentEpisodeLabel().ifBlank { null }
        val content = contentKey.ifBlank { key }
        persistScope.launch {
            history.save(
                mediaKey = content,
                groupKey = content,
                title = title,
                posterUrl = displayPosterUrl,
                streamUrl = key,
                positionMs = position,
                durationMs = duration,
                episodeLabel = epLabel
            )
        }
    }

    fun pause() {
        player.pause()
        saveProgressNow()
    }

    /** Display label for the episode currently loaded (real name when known). */
    private fun currentEpisodeLabel(): String {
        val ep = playbackSession.current() ?: return displayEpisodeLabel
        val name = playbackSession.nameFor(ep.season, ep.episode)
            ?: "Episode ${ep.episode ?: (_episodeIndex.value + 1)}"
        val code = "S" + (ep.season ?: 0).toString().padStart(2, '0') +
            "E" + (ep.episode ?: 0).toString().padStart(2, '0')
        return "$code \u00b7 $name"
    }

    /** Player top-bar title: episode label for series, show/movie title otherwise, never the raw source filename. */
    private fun initialTitle(): String = displayEpisodeLabel.ifBlank { displayTitle }

    /** Switches playback to another episode of the session's series. */
    fun playEpisode(index: Int) {
        val episodes = playbackSession.episodes
        if (index !in episodes.indices) return
        val ep = episodes[index]
        if (isEpisodeUpcoming(ep)) {
            _ui.update { it.copy(notice = "This episode hasn't aired yet.", buffering = false) }
            return
        }
        // TMDB-only entry: resolve a stream first.
        if (ep.url.isBlank()) {
            resolveAndPlayEpisode(index)
            return
        }
        saveProgressNow()
        playbackSession.currentIndex = index
        _episodeIndex.value = index
        nextUpHidden = false
        _showNextUp.value = false
        retried = false
        rejectedUrls.clear()
        altAttempts = 0
        altInFlight = false
        mediaKey = ep.url
        lastKnownPositionMs = 0
        _ui.update {
            it.copy(title = currentEpisodeLabel(), error = null, notice = null, buffering = true)
        }
        resetVideoSize()
        viewModelScope.launch {
            applyRequestHeaders(ep.headers)
            player.setMediaItem(MediaItem.fromUri(ep.url.toUri()))
            player.prepare()
            player.playWhenReady = true
        }
    }

    // TMDB-only episode: find a stream, patch the session, then play.
    private fun resolveAndPlayEpisode(index: Int) {
        val ep = playbackSession.episodes.getOrNull(index) ?: return
        _ui.update {
            it.copy(title = currentEpisodeLabel(), error = null, notice = null, buffering = true)
        }
        viewModelScope.launch {
            val meta = resolveMetadata()
            val resolved = seriesResolver.alternateEpisode(
                title = displayTitle,
                year = displayYear,
                imdbId = meta?.imdbId,
                seasonCount = meta?.numberOfSeasons,
                season = ep.season,
                episode = ep.episode,
                failedUrl = "",
                exclude = emptySet(),
            )
            if (resolved == null) {
                _ui.update {
                    it.copy(buffering = false, error = "No source has this episode yet.")
                }
                return@launch
            }
            playbackSession.episodes = playbackSession.episodes
                .toMutableList()
                .also { it[index] = resolved }
            playEpisode(index)
        }
    }

    fun playNextEpisode() {
        playEpisode(_episodeIndex.value + 1)
    }

    fun dismissNextUp() {
        nextUpHidden = true
        _showNextUp.value = false
    }

    /** Real episode name when TMDB has it, else "Episode N". */
    fun episodeNameFor(ep: EpisodeStream): String =
        playbackSession.nameFor(ep.season, ep.episode)
            ?: "Episode ${ep.episode ?: ""}"

    fun episodeDateFor(ep: EpisodeStream): String? =
        playbackSession.episodeDates[ep.season]?.get(ep.episode)

    fun isEpisodeUpcoming(ep: EpisodeStream): Boolean = isUpcoming(episodeDateFor(ep))

    fun nextEpisodeLabel(): String? {
        val next = playbackSession.next() ?: return null
        val name = playbackSession.nameFor(next.season, next.episode)
            ?: "Episode ${next.episode ?: ""}"
        val code = "S" + (next.season ?: 0).toString().padStart(2, '0') +
            "E" + (next.episode ?: 0).toString().padStart(2, '0')
        return "$code \u00b7 $name"
    }

    fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        if (visible && player.isPlaying) startPositionUpdates()
    }

    /** Ticks position while playing, independent of controls (Next-Up needs it). */
    private fun startPositionUpdates() {
        if (progressLoop?.isActive == true) return
        progressLoop = viewModelScope.launch {
            while (isActive && player.isPlaying) {
                delay(500.milliseconds)
                val duration = player.duration.coerceAtLeast(0L)
                if (duration > 0) {
                    val position = player.currentPosition
                    if (position > 0) lastKnownPositionMs = position
                    _progress.value = Progress(if (position > 0) position else lastKnownPositionMs, duration)
                }
            }
        }
    }

    /** Live "stats for nerds" sampling: refresh the current bitrate/position/buffer into stats. */
    private fun startStatsSampling() {
        viewModelScope.launch {
            while (isActive) {
                delay(500.milliseconds)
                refreshStats()
            }
        }
    }

    private fun refreshStats() {
        // Live bitrate from the bandwidth meter, not the container-declared value.
        val measuredKbps = (latestBandwidthBps / 1000L).coerceAtLeast(0L)
        val effectiveKbps = if (measuredKbps > 0) measuredKbps else declaredKbps
        currentKbps = effectiveKbps
        if (effectiveKbps > 0) {
            bitrateHistory.addLast(effectiveKbps)
            if (bitrateHistory.size > MAX_BITRATE_SAMPLES) bitrateHistory.removeFirst()
        }
        _stats.value = PlayerStats(
            positionMs = bestKnownPositionMs(),
            durationMs = player.duration.coerceAtLeast(0L),
            bufferedMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L),
            videoBitrateKbps = effectiveKbps,
            videoCodec = videoCodec,
            videoWidth = player.videoSize.width,
            videoHeight = player.videoSize.height,
            audioCodec = audioCodec,
            audioChannels = audioChannels,
            containerMime = containerMime,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            bitrateHistory = bitrateHistory.toList()
        )
    }

    /** Capture video/audio codec + container from the player's track groups (static-ish; refresh each call). */
    private fun captureTracks() {
        val tracks = player.currentTracks
        var vCodec: String? = null
        var vWidth = 0
        var vHeight = 0
        var aCodec: String? = null
        var aCh = 0
        var mime: String? = null
        for (group in tracks.groups) {
            val track = group.mediaTrackGroup.getFormat(0)
            if (group.type == C.TRACK_TYPE_VIDEO) {
                vCodec = track.codecs ?: track.sampleMimeType
                vWidth = track.width
                vHeight = track.height
                mime = track.containerMimeType ?: track.sampleMimeType
                // Format.bitrate is the media's declared bitrate (bps); keep as fallback (kbps).
                if (track.bitrate > 0) {
                    declaredKbps = track.bitrate / 1000L
                }
            } else if (group.type == C.TRACK_TYPE_AUDIO) {
                aCodec = track.codecs ?: track.sampleMimeType
                aCh = track.channelCount
            }
        }
        videoCodec = vCodec
        audioCodec = aCodec
        audioChannels = aCh
        containerMime = mime ?: containerMime
        _stats.update { it.copy(
            videoCodec = vCodec,
            videoWidth = vWidth,
            videoHeight = vHeight,
            audioCodec = aCodec,
            audioChannels = aCh,
            containerMime = mime ?: it.containerMime
        ) }
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
            saveProgressNow()
        } else {
            player.play()
        }
    }

    fun seekBy(deltaMs: Long) {
        // bestKnown: a transient 0 readout (mid-recovery) must not turn a forward skip into a jump to ~10s.
        seekTo((bestKnownPositionMs() + deltaMs).coerceAtLeast(0L))
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        lastKnownPositionMs = positionMs
        _progress.value = Progress(positionMs, player.duration.coerceAtLeast(0L))
    }

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _ui.update { it.copy(speed = speed) }
        viewModelScope.launch { playbackSettings.setPlaybackSpeed(speed) }
    }

    // Fit modes drive surface size; the renderer fills whatever rect it gets.
    fun setFitMode(mode: FitMode) {
        _ui.update { it.copy(fitMode = mode) }
        viewModelScope.launch { playbackSettings.setFitMode(mode) }
    }

    fun toggleLock() {
        _ui.update { it.copy(locked = !it.locked) }
    }

    /** Applies track picks; subtitles are only touched when [touchSubtitles], so an audio-only pick never disables them. */
    fun selectTracks(
        audioGroupIndex: Int?,
        audioTrackIndex: Int?,
        subtitleGroupIndex: Int?,
        subtitleTrackIndex: Int?,
        touchSubtitles: Boolean
    ) {
        val tracks = player.currentTracks
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .apply {
                    if (audioGroupIndex != null && audioTrackIndex != null &&
                        audioGroupIndex in tracks.groups.indices
                    ) {
                        val group = tracks.groups[audioGroupIndex]
                        if (audioTrackIndex in 0 until group.length) {
                            addOverride(
                                androidx.media3.common.TrackSelectionOverride(
                                    group.mediaTrackGroup,
                                    audioTrackIndex
                                )
                            )
                        }
                    }
                    if (touchSubtitles) {
                        clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        // "Off" disables the text type so embedded subtitles stay off.
                        setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitleGroupIndex == null)
                        if (subtitleGroupIndex != null && subtitleTrackIndex != null &&
                            subtitleGroupIndex in tracks.groups.indices
                        ) {
                            val group = tracks.groups[subtitleGroupIndex]
                            if (subtitleTrackIndex in 0 until group.length) {
                                addOverride(
                                    androidx.media3.common.TrackSelectionOverride(
                                        group.mediaTrackGroup,
                                        subtitleTrackIndex
                                    )
                                )
                            }
                        }
                    }
                }
            .build()
    }

    /** Re-prepares the current item after a play failure. */
    fun retryPlayback() {
        _ui.update { it.copy(error = null, buffering = true) }
        retried = false
        if (player.currentMediaItem != null) {
            player.prepare()
            player.playWhenReady = true
        } else {
            mediaKey?.let { key -> viewModelScope.launch { open(key) } }
        }
    }

    private fun playUrl(url: String, positionMs: Long) {
        resetVideoSize()
        lastKnownPositionMs = positionMs
        applyRequestHeaders()
        player.setMediaItem(MediaItem.fromUri(url.toUri()))
        player.seekTo(positionMs)
        player.prepare()
        player.playWhenReady = true
    }

    private fun retrySameItem(positionMs: Long) {
        val item = player.currentMediaItem ?: return
        resetVideoSize()
        lastKnownPositionMs = positionMs
        player.clearMediaItems()
        player.setMediaItem(item)
        player.seekTo(positionMs)
        player.prepare()
        player.playWhenReady = true
    }

    /** Tries another source/quality after a decoder failure (e.g. HEVC 10-bit on unsupported hardware). */
    private fun tryAlternateSource(pos: Long, notice: String = DECODER_ALT_NOTICE) {
        val failed = mediaKey ?: return
        if (altInFlight) return
        altInFlight = true
        rejectedUrls += failed
        if (altAttempts++ >= MAX_ALT_ATTEMPTS) {
            altInFlight = false
            _ui.update {
                it.copy(error = "No playable version found for this device.", notice = null, buffering = false)
            }
            return
        }
        _ui.update {
            it.copy(
                buffering = true,
                notice = notice,
            )
        }
        viewModelScope.launch {
            try {
                val title = displayTitle.ifBlank { displayEpisodeLabel.ifBlank { "" } }
                if (title.isBlank()) {
                    _ui.update {
                        it.copy(
                            error = "No playable version found for this device.",
                            notice = null,
                            buffering = false
                        )
                    }
                    return@launch
                }
                val meta = resolveMetadata()
                val altUrl: String? = if (playbackSession.hasEpisodes) {
                    seriesResolver.alternateEpisode(
                        title = title,
                        year = displayYear,
                        imdbId = meta?.imdbId,
                        seasonCount = meta?.numberOfSeasons,
                        season = playbackSession.current()?.season,
                        episode = playbackSession.current()?.episode,
                        failedUrl = failed,
                        exclude = rejectedUrls,
                    )?.url
                } else {
                    streamResolver.resolveAlternate(
                        title = title,
                        year = displayYear,
                        imdbId = meta?.imdbId,
                        exclude = rejectedUrls,
                    )?.url
                }
                if (altUrl == null) {
                    _ui.update {
                        it.copy(
                            error = "No playable version found for this device.",
                            notice = null,
                            buffering = false
                        )
                    }
                } else {
                    mediaKey = altUrl
                    retried = false
                    _ui.update { it.copy(notice = null) }
                    playUrl(altUrl, pos)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _ui.update {
                    it.copy(error = "Could not play this file. Please try again.", notice = null, buffering = false)
                }
            } finally {
                altInFlight = false
            }
        }
    }

    // Apply the current stream's extra HTTP request headers to the data source.
    private fun applyRequestHeaders(headers: Map<String, String> = streamHeaders) {
        httpDataSourceFactory.setDefaultRequestProperties(headers)
    }

    override fun onCleared() {
        // Player is still valid on the main thread here; capture then persist.
        saveProgressNow()
        player.release()
    }

    private companion object {
        const val NEXT_UP_WINDOW_MS = 75_000L
        const val READ_TIMEOUT_MS = 60_000
        const val MAX_BITRATE_SAMPLES = 120
        private const val TAG = "PlayerViewModel"
        /** Decoder-related PlaybackException codes (video/audio codec failures, not network). */
        private val DECODER_ERRORS =
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..
                PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
        private const val MAX_ALT_ATTEMPTS = 3
        private const val DECODER_ALT_NOTICE =
            "Your device can't decode this video format. Trying an alternate source..."
        private const val SOURCE_RETRY_NOTICE =
            "This stream is not responding. Trying an alternate source..."
    }
}
