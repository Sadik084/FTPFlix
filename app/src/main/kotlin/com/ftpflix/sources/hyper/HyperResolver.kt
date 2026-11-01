package com.ftpflix.sources.hyper

import android.util.Log
import com.ftpflix.core.util.formatBytes
import com.ftpflix.sources.HttpProbe
import com.ftpflix.sources.resolve.ResolvedStream
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds
import javax.inject.Inject
import javax.inject.Singleton

// Hyper source: direct HTTP streams by IMDb id; locked/premium entries filtered out.
@Singleton
class HyperResolver @Inject constructor(
    private val api: HyperApi,
    private val httpProbe: HttpProbe,
) {
    private val resolutionRegex = Regex("""\b(2160|1440|1080|720|576|480)p\b""", RegexOption.IGNORE_CASE)

    suspend fun resolveMovie(imdbId: String?, preferredHeight: Int?): ResolvedStream? {
        val tt = imdbId?.trim()?.takeIf { it.isNotEmpty() }
        if (tt == null) {
            Log.w(TAG, "hyper skip: no imdb id available")
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
            Log.w(TAG, "hyper skip: no imdb id available")
            return null
        }
        val streams = withTimeoutOrNull(FETCH_TIMEOUT_MS.seconds) {
            api.fetchStreams("series", "$tt:$season:$episode")
        } ?: emptyList()
        return pickBest(streams, preferredHeight)
    }

    private suspend fun pickBest(
        streams: List<HyperStream>,
        preferredHeight: Int?,
    ): ResolvedStream? {
        val usable = streams.mapNotNull { parseUsable(it) }
        if (usable.isEmpty()) {
            Log.w(TAG, "hyper no usable streams (loaded=${streams.size})")
            return null
        }

        val target = preferredHeight ?: DEFAULT_AUTO_HEIGHT
        // Closest tier to the requested height, then higher res.
        val chosen = usable.minWith(
            compareBy<Usable> { it.distanceTo(target) }
                .thenByDescending(Usable::height),
        )

        val sizeDisplay = chosen.videoSize?.let { formatBytes(it) }
            ?: withTimeoutOrNull(3.seconds) { httpProbe.contentLength(chosen.url, chosen.headers) }
                ?.let { formatBytes(it) }
                ?: ""
        Log.d(TAG, "hyper picked ${chosen.height}p -> ${chosen.url}")
        return ResolvedStream(chosen.url, HyperApi.HOST_LABEL, sizeDisplay, chosen.headers)
    }

    private data class Usable(
        val url: String,
        val height: Int,
        val videoSize: Long?,
        val headers: Map<String, String>,
    ) {
        fun distanceTo(target: Int): Int = kotlin.math.abs(height - target)
    }

    // Keep only real streamable entries: a direct media url, not a premium/locked/action link.
    private fun parseUsable(s: HyperStream): Usable? {
        val url = s.url?.trim().orEmpty()
        if (url.isBlank() || !url.startsWith("http")) return null
        if (ARCHIVE_EXT.containsMatchIn(url)) return null

        val title = s.title.orEmpty()
        val height = resolutionRegex.find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: when {
                title.contains("4K", ignoreCase = true) || title.contains("UHD", ignoreCase = true) -> 2160
                else -> 0
            }
        val headers = s.behaviorHints?.proxyHeaders?.request
            ?.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let { k to it } }
            ?.toMap() ?: emptyMap()
        return Usable(url, height, s.behaviorHints?.videoSize, headers)
    }

    companion object {
        private const val TAG = "HyperResolver"
        private const val FETCH_TIMEOUT_MS = 12L
        private val ARCHIVE_EXT = Regex("""(?i)\.(zip|rar|7z|tar|gz|bz2|xz)$""")
        /** "Auto" quality targets 1080p (falls to the nearest tier when absent). */
        private const val DEFAULT_AUTO_HEIGHT = 1080
    }
}
