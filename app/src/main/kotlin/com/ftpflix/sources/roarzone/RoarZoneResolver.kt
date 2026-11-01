package com.ftpflix.sources.roarzone

import android.util.Log
import com.ftpflix.sources.HttpProbe
import com.ftpflix.sources.resolve.EpisodeStream
import com.ftpflix.sources.resolve.ResolvedStream
import com.ftpflix.util.TitleMatchingUtils
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import javax.inject.Inject
import javax.inject.Singleton

// RoarZone Emby source: movies by IMDb id, series per season.
@Singleton
class RoarZoneResolver @Inject constructor(
    private val api: RoarZoneApi,
    private val httpProbe: HttpProbe,
) {
    private val resolutionRegex = Regex("""\b(2160|1440|1080|720|576|480)p\b""")

    private fun EmbyItem.hasPlayableSource(): Boolean = !id.isNullOrBlank() && mediaSources.isNotEmpty()

    private fun EmbyItem.hasId(): Boolean = !id.isNullOrBlank()

    private fun EmbyItem.matchesQuery(imdbId: String?, title: String): Boolean =
        (imdbId != null) || matchesTitle(name.orEmpty(), year, title, queryYear = null)

    suspend fun resolveMovie(
        imdbId: String?,
        title: String,
        year: Int?,
        preferredHeight: Int?,
    ): ResolvedStream? {
        val items = api.searchMovies(imdbId, title)
        // With an IMDb id the server matched exactly; the title gate is only for SearchTerm fallback.
        val candidates = items.filter {
            it.hasPlayableSource() && ((imdbId != null) || matchesTitle(it.name.orEmpty(), it.year, title, year))
        }
        if (candidates.isEmpty()) return null

        val token = api.currentToken() ?: return null
        var best: Pair<String, Int>? = null
        for (item in candidates) {
            val url = api.streamUrl(item.id!!, token)
            val score = movieScore(title, item, preferredHeight)
            if (score <= 0) continue
            if ((best == null) || (score > best.second)) best = url to score
        }
        val (winnerUrl, winnerScore) = best ?: return null

        val sizeDisplay = probeSize(winnerUrl)
        Log.d(TAG, "RoarZone resolved '$title' -> $winnerUrl (score=$winnerScore)")
        return ResolvedStream(winnerUrl, RoarZoneApi.HOST_LABEL, sizeDisplay)
    }

    // Season sweep; stops at the first empty season.
    suspend fun resolveSeries(
        imdbId: String?,
        title: String,
        seasonCount: Int?,
        preferredHeight: Int?,
    ): List<EpisodeStream> {
        val seriesId = api.searchSeries(imdbId, title)
            .firstOrNull { it.hasId() }
            ?.takeIf { it.matchesQuery(imdbId, title) }
            ?.id
            ?: return emptyList()

        val token = api.currentToken() ?: return emptyList()
        val known = seasonCount?.coerceIn(1, MAX_SEASONS) ?: 1
        val episodes = ArrayList<EpisodeStream>()
        for (season in 1..MAX_SEASONS) {
            val eps = runCatching { api.episodes(seriesId, season) }.getOrDefault(emptyList())
            if (eps.isEmpty()) {
                // Keep probing up to the known season count.
                if (season >= known) break
                continue
            }
            for (ep in eps) {
                val id = ep.id ?: continue
                val epNum = ep.indexNumber ?: continue
                // The addon requires MediaSources to be present before offering a stream.
                if (ep.mediaSources.isEmpty()) continue
                episodes += EpisodeStream(
                    url = api.streamUrl(id, token),
                    host = RoarZoneApi.HOST_LABEL,
                    title = ep.name.orEmpty(),
                    season = ep.parentIndexNumber ?: season,
                    episode = epNum,
                    sizeDisplay = "",
                    score = episodeScore(ep.qualityText, preferredHeight),
                )
            }
        }
        if (episodes.isNotEmpty()) {
            Log.d(TAG, "RoarZone resolved series '$title': ${episodes.size} episodes")
        }
        return episodes
    }

    // Same strict token gate as CircleFtpResolver.
    private fun matchesTitle(itemName: String, itemYear: Int?, queryTitle: String, queryYear: Int?): Boolean {
        val queryTokens = TitleMatchingUtils.extractMeaningfulTokens(queryTitle)
        if (queryTokens.isNotEmpty()) {
            val itemTokens = TitleMatchingUtils.extractMeaningfulTokens(itemName)
            val allPresent = queryTokens.all { token ->
                itemTokens.any { (it == token) || it.startsWith(token) || token.startsWith(it) }
            }
            if (!allPresent) return false
        }
        return (itemYear == null) || (queryYear == null) || (kotlin.math.abs(itemYear - queryYear) <= 1)
    }

    // Title similarity dominates; resolution breaks ties.
    private fun movieScore(queryTitle: String, item: EmbyItem, preferredHeight: Int?): Int {
        val text = "${item.qualityText} ${item.name.orEmpty()}"
        var score = 200

        val queryTokens = TitleMatchingUtils.extractMeaningfulTokens(queryTitle)
        val nameTokens = TitleMatchingUtils.extractMeaningfulTokens(item.name.orEmpty())
        score += when {
            queryTokens.isNotEmpty() && nameTokens.containsAll(queryTokens) -> 300
            else -> queryTokens.count { it in nameTokens } * 40
        }

        val res = resolutionRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
        score += when {
            res == null -> 2
            preferredHeight == null -> when {
                res >= 2160 -> 40
                res >= 1080 -> 30
                res >= 720 -> 20
                else -> 10
            }
            res == preferredHeight -> 50
            else -> 15
        }
        val path = item.mediaSources.firstOrNull()?.path.orEmpty()
        when (path.substringAfterLast('.', "").lowercase()) {
            "mp4" -> score += 8
            "mkv" -> score += 6
        }
        return score
    }

    private fun episodeScore(text: String, preferredHeight: Int?): Int {
        var score = 150
        val res = resolutionRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
        score += when {
            res == null -> 2
            preferredHeight == null -> if (res >= 1080) 20 else 10
            res == preferredHeight -> 30
            else -> 10
        }
        return score
    }

    // Best-effort content-length probe for the display size.
    private suspend fun probeSize(url: String): String {
        val size = withTimeoutOrNull(3.seconds) { httpProbe.contentLength(url) }
        return size?.let { com.ftpflix.core.util.formatBytes(it) } ?: ""
    }

    companion object {
        private const val TAG = "RoarZoneResolver"
        private const val MAX_SEASONS = 30
    }
}
