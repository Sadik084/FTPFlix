package com.ftpflix.sources.circleapi

import android.util.Log
import com.ftpflix.domain.parser.PathEpisodeParser
import com.ftpflix.sources.HttpProbe
import com.ftpflix.sources.resolve.EpisodeStream
import com.ftpflix.sources.resolve.ResolvedStream
import com.ftpflix.util.TitleMatchingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import javax.inject.Inject
import javax.inject.Singleton

// Resolves movies and series through the CircleFTP API.
@Singleton
class CircleFtpResolver @Inject constructor(
    private val api: CircleFtpApi,
    private val httpProbe: HttpProbe,
) {
    private val resolutionRegex = Regex("""\b(2160|1440|1080|720|576|480)p\b""")
    private val seasonNumRegex = Regex("""(\d+)""")

    private fun CirclePost.isMovie(): Boolean = type == CircleFtpApi.TYPE_MOVIE

    private fun CirclePost.isSeries(): Boolean = type == CircleFtpApi.TYPE_SERIES

    suspend fun resolveMovie(
        title: String,
        year: Int?,
        preferredHeight: Int?,
    ): ResolvedStream? {
        val posts = withTimeoutOrNull(SEARCH_TIMEOUT_MS.seconds) { api.search(title) } ?: return null
        val matches = posts.filter { it.isMovie() && matchesPost(title, year, it) }
        if (matches.isEmpty()) return null

        val candidates = coroutineScope {
            matches.take(MAX_DETAILS).map { (postId, _, _, _, _, qualityHint) ->
                async(Dispatchers.IO) {
                    val detail = runCatching { api.detail(postId) }.getOrNull() ?: return@async null
                    val url = api.movieUrl(detail) ?: return@async null
                    url to qualityHint.orEmpty()
                }
            }.awaitAll().filterNotNull()
        }
        if (candidates.isEmpty()) return null

        var best: Pair<String, Int>? = null
        for ((url, qualityHint) in candidates) {
            val score = movieScore(title, url, qualityHint, preferredHeight)
            if (score <= 0) continue
            if ((best == null) || (score > best.second)) best = url to score
        }
        val (winnerUrl, winnerScore) = best ?: return null

        val sizeDisplay = probeSize(winnerUrl)
        Log.d(TAG, "API resolved '$title' -> $winnerUrl (score=$winnerScore)")
        return ResolvedStream(winnerUrl, CircleFtpApi.HOST_LABEL, sizeDisplay)
    }

    suspend fun resolveSeries(
        title: String,
        year: Int?,
        preferredHeight: Int?,
    ): List<EpisodeStream> {
        val posts = withTimeoutOrNull(SEARCH_TIMEOUT_MS.seconds) { api.search(title) } ?: return emptyList()
        val matches = posts.filter { it.isSeries() && matchesPost(title, year, it) }
        if (matches.isEmpty()) return emptyList()

        // Search may return other shows with the same name. Keep only the best match.
        val bestPost = bestSeriesPost(title, year, matches) ?: return emptyList()

        val episodes = ArrayList<EpisodeStream>()
        val detail = runCatching { api.detail(bestPost.id) }.getOrNull() ?: return emptyList()
        val seasons = api.seasons(detail)
        for ((seasonIdx, season) in seasons.withIndex()) {
            val seasonNumber = season.seasonName?.let { seasonNumRegex.find(it)?.value?.toIntOrNull() }
                ?: (seasonIdx + 1)
            for ((epIdx, ep) in season.episodes.withIndex()) {
                val link = ep.link ?: continue
                val (parsedSeason, parsedEpisode) = PathEpisodeParser.parse(ep.title.orEmpty())
                episodes += EpisodeStream(
                    url = link,
                    host = CircleFtpApi.HOST_LABEL,
                    title = ep.title.orEmpty(),
                    season = parsedSeason ?: seasonNumber,
                    episode = parsedEpisode ?: (epIdx + 1),
                    sizeDisplay = "",
                    score = episodeScore(ep.title.orEmpty(), preferredHeight),
                )
            }
        }
        if (episodes.isNotEmpty()) {
            Log.d(TAG, "API resolved series '$title': ${episodes.size} episodes")
        }
        return episodes
    }

    // All title words must be in the candidate, then the year is checked.
    private fun matchesPost(queryTitle: String, queryYear: Int?, post: CirclePost): Boolean {
        val queryTokens = TitleMatchingUtils.extractMeaningfulTokens(queryTitle)
        if (queryTokens.isNotEmpty()) {
            val candidateTokens = TitleMatchingUtils.extractMeaningfulTokens(post.displayName)
            val allPresent = queryTokens.all { token ->
                candidateTokens.any { (it == token) || it.startsWith(token) || token.startsWith(it) }
            }
            if (!allPresent) return false
        }
        val postYear = post.year?.trim()?.take(4)?.toIntOrNull()
        return (postYear == null) || (queryYear == null) || (kotlin.math.abs(postYear - queryYear) <= 1)
    }

    // Picks the one series post that really matches, so similar shows don't mix.
    private fun bestSeriesPost(
        queryTitle: String,
        queryYear: Int?,
        contenders: List<CirclePost>,
    ): CirclePost? {
        if (contenders.isEmpty()) return null
        val queryClean = TitleMatchingUtils.cleanTitle(queryTitle)

        // Exact name match wins.
        contenders.firstOrNull { TitleMatchingUtils.cleanTitle(it.displayName) == queryClean }
            ?.let { return it }

        // Otherwise use the closest year, then the fewest extra words.
        return contenders.minWithOrNull(
            compareBy<CirclePost> {
                val postYear = it.year?.trim()?.take(4)?.toIntOrNull()
                if (queryYear != null && postYear != null) kotlin.math.abs(postYear - queryYear) else 0
            }.thenBy {
                TitleMatchingUtils.extractMeaningfulTokens(it.displayName).size
            },
        )
    }

    // Rank by title match, then by resolution.
    private fun movieScore(
        queryTitle: String,
        url: String,
        qualityHint: String,
        preferredHeight: Int?,
    ): Int {
        val text = "$qualityHint ${url.substringAfterLast('/')}"
        var score = 200

        val queryClean = TitleMatchingUtils.cleanTitle(queryTitle)
        val fileClean = TitleMatchingUtils.cleanTitle(url.substringAfterLast('/'))
        val queryTokens = TitleMatchingUtils.extractMeaningfulTokens(queryTitle)
        val fileTokens = TitleMatchingUtils.extractMeaningfulTokens(url.substringAfterLast('/'))
        score += when {
            queryClean.isNotEmpty() && (queryClean == fileClean) -> 500
            queryTokens.isNotEmpty() && fileTokens.containsAll(queryTokens) -> 300
            else -> {
                val overlap = queryTokens.count { it in fileTokens }
                overlap * 40
            }
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
        when (url.substringAfterLast('.', "").lowercase()) {
            "mp4" -> score += 8
            "mkv" -> score += 6
        }
        return score
    }

    private fun episodeScore(title: String, preferredHeight: Int?): Int {
        var score = 150
        val res = resolutionRegex.find(title)?.groupValues?.get(1)?.toIntOrNull()
        score += when {
            res == null -> 2
            preferredHeight == null -> if (res >= 1080) 20 else 10
            res == preferredHeight -> 30
            else -> 10
        }
        return score
    }

    // Check file size for display.
    private suspend fun probeSize(url: String): String {
        val size = withTimeoutOrNull(3.seconds) { httpProbe.contentLength(url) }
        return size?.let { com.ftpflix.core.util.formatBytes(it) } ?: ""
    }

    companion object {
        private const val TAG = "CircleFtpResolver"
        private const val SEARCH_TIMEOUT_MS = 8_000L
        private const val MAX_DETAILS = 5
    }
}
