package com.ftpflix.sources.hdhub

import android.util.Log
import com.ftpflix.core.util.formatBytes
import com.ftpflix.sources.HttpProbe
import com.ftpflix.sources.resolve.ResolvedStream
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import javax.inject.Inject
import javax.inject.Singleton

// HdHub source: streams by IMDb id; promo/download-only entries filtered out.
@Singleton
class HdHubResolver @Inject constructor(
    private val api: HdHubApi,
    private val httpProbe: HttpProbe,
) {
    private val resolutionRegex = Regex("""\b(2160|1440|1080|720|576|480)p\b""")

    suspend fun resolveMovie(imdbId: String?, preferredHeight: Int?): ResolvedStream? {
        val tt = imdbId?.trim()?.takeIf { it.isNotEmpty() }
        if (tt == null) {
            Log.w(TAG, "hdhub skip: no imdb id available")
            return null
        }
        val streams = withTimeoutOrNull(FETCH_TIMEOUT_MS.seconds) {
            api.fetchStreams("movie", tt)
        } ?: emptyList()
        return pickBest(streams, preferredHeight)
    }

    // Distinct quality heights currently offered, best first.
    suspend fun availableHeights(type: String, id: String): List<Int> {
        if (id.isBlank()) return emptyList()
        val streams = withTimeoutOrNull(FETCH_TIMEOUT_MS.seconds) {
            api.fetchStreams(type, id)
        } ?: emptyList()
        return streams.asSequence().mapNotNull { parseUsable(it)?.height }
            .filter { it > 0 }
            .distinct()
            .sortedDescending()
            .toList()
    }

    suspend fun resolveEpisode(
        imdbId: String?,
        season: Int,
        episode: Int,
        preferredHeight: Int?,
    ): ResolvedStream? {
        val tt = imdbId?.trim()?.takeIf { it.isNotEmpty() }
        if (tt == null) {
            Log.w(TAG, "hdhub skip: no imdb id available")
            return null
        }
        val streams = withTimeoutOrNull(FETCH_TIMEOUT_MS.seconds) {
            api.fetchStreams("series", "$tt:$season:$episode")
        } ?: emptyList()
        return pickBest(streams, preferredHeight)
    }

    private suspend fun pickBest(
        streams: List<HdHubStream>,
        preferredHeight: Int?,
    ): ResolvedStream? {
        val usable = streams.mapNotNull { parseUsable(it) }
        if (usable.isEmpty()) {
            Log.w(TAG, "hdhub no usable streams (loaded=${streams.size})")
            return null
        }

        val target = preferredHeight ?: DEFAULT_AUTO_HEIGHT
        // Closest tier to the requested height, friendlier codec first, then higher res.
        val (chosenUrl, chosenHeight, chosenVideoSize, _) = usable.minWith(
            compareBy<Usable> { it.distanceTo(target) }
                .thenByDescending(Usable::friendly)
                .thenByDescending(Usable::height),
        )

        val sizeDisplay = chosenVideoSize?.let { formatBytes(it) }
            ?: withTimeoutOrNull(3.seconds) { httpProbe.contentLength(chosenUrl) }
                ?.let { formatBytes(it) }
                ?: ""
        Log.d(TAG, "hdhub picked ${chosenHeight}p -> $chosenUrl")
        return ResolvedStream(chosenUrl, HdHubApi.HOST_LABEL, sizeDisplay)
    }

    private data class Usable(
        val url: String,
        val height: Int,
        val videoSize: Long?,
        // True when the encode is H.264/AVC (x264), safe for hardware decoders.
        val friendly: Boolean,
    )

    private fun Usable.distanceTo(target: Int): Int = kotlin.math.abs(height - target)

    private val friendlyCodec = Regex(
        """(?i)(x264|h264|avc|\bh\.264\b)"""
    )

    // Keep only real, streamable files (drop promos + download-only gateways).
    private fun parseUsable(s: HdHubStream): Usable? {
        val url = s.url?.trim().orEmpty()
        if (url.isBlank()) return null
        if (BLOCKED_HOSTS.any { url.contains(it) }) return null
        if (ARCHIVE_EXT.containsMatchIn(url)) return null

        val blob = "${s.name} ${s.description}"
        if (blob.contains("Download Only", ignoreCase = true)) return null

        val height = resolutionRegex.find(blob)?.groupValues?.get(1)?.toIntOrNull()
            ?: when {
                blob.contains("4K", ignoreCase = true) || blob.contains("UHD", ignoreCase = true) -> 2160
                else -> 0
            }
        val friendly = friendlyCodec.containsMatchIn(blob)
        return Usable(url, height, s.behaviorHints?.videoSize, friendly)
    }

    companion object {
        private const val TAG = "HdHubResolver"
        private const val FETCH_TIMEOUT_MS = 12L
        // Hosts that serve download pages/bundles rather than streamable video.
        private val BLOCKED_HOSTS = listOf("hubcloud.ist", "pixeldrain.dev")
        private val ARCHIVE_EXT = Regex("""(?i)\.(zip|rar|7z|tar|gz|bz2|xz)$""")
        /** "Auto" quality targets 1080p (falls to the nearest tier when absent). */
        private const val DEFAULT_AUTO_HEIGHT = 1080
    }
}
