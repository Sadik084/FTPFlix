package com.ftpflix.sources.resolve

import android.util.Log
import com.ftpflix.core.config.SourceHealthStore
import com.ftpflix.core.config.SourceSettingsStore
import com.ftpflix.domain.parser.TitleNormalizer
import com.ftpflix.sources.circleapi.CircleFtpResolver
import com.ftpflix.sources.roarzone.RoarZoneResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

// TV series resolution, same user-ordered cascade as movies.
@Singleton
class SeriesResolver @Inject constructor(
    private val circleApi: CircleFtpResolver,
    private val roarZone: RoarZoneResolver,
    private val sourceHealth: SourceHealthStore,
    private val sourceSettings: SourceSettingsStore,
) {
    private val catalogCache = MemoryCache<String, SeriesCatalog>(32, CATALOG_TTL_MS)

    suspend fun resolveSeries(
        title: String,
        year: Int?,
        imdbId: String? = null,
        seasonCount: Int? = null,
    ): SeriesCatalog? {
        val startedAt = System.currentTimeMillis()
        val key = TitleNormalizer.key(title, year)
        Log.d(TAG, "resolveSeries title='$title', year=$year, key='$key'")

        catalogCache.get(key)?.let {
            Log.d(TAG, "Catalog cache hit for '$title' (${it.seasons.size} seasons)")
            return it
        }

        // Sequential, user-ordered (Settings): enabled sources tried in order until one yields episodes.
        val episodes = ArrayList<EpisodeStream>()
        val order = sourceSettings.sourceOrder.first()
        val enabled = sourceSettings.enabledSources.first()
        for (s in order) {
            if (s !in enabled) continue
            when (s) {
                StreamSource.CIRCLE_FTP -> {
                    if (sourceHealth.circleFtpEnabled.first()) {
                        episodes += runCatchingSuspend {
                            withTimeoutOrNull(API_TIMEOUT_MS.milliseconds) {
                                circleApi.resolveSeries(title, year, null)
                            }
                        }.getOrNull().orEmpty()
                    }
                }
                StreamSource.ROAR_ZONE -> {
                    if (sourceHealth.roarZoneEnabled.first()) {
                        episodes += runCatchingSuspend {
                            withTimeoutOrNull(API_TIMEOUT_MS.milliseconds) {
                                roarZone.resolveSeries(imdbId, title, seasonCount, null)
                            }
                        }.getOrNull().orEmpty()
                    }
                }
                // HdHub has no browse endpoint; series resolve per-episode at play time.
                StreamSource.HD_HUB, StreamSource.AUTO -> {}
            }
            if (episodes.isNotEmpty()) break
        }

        if (episodes.isEmpty()) {
            Log.w(TAG, "No episodes found for series '$title' (${System.currentTimeMillis() - startedAt} ms)")
            return null
        }

        val catalog = buildCatalog(title, episodes)
        catalogCache.put(key, catalog)
        val catalogSeasons = catalog.seasons.size
        val catalogStreams = episodes.size
        val elapsedMs = System.currentTimeMillis() - startedAt
        Log.d(TAG, "Series '$title' catalog: $catalogSeasons seasons, $catalogStreams episode streams ($elapsedMs ms)")
        return catalog
    }

    // Rebuilds a catalog from a pinned source; HdHub returns null (resolves per episode).
    suspend fun resolveSeriesPinned(
        source: StreamSource,
        title: String,
        year: Int?,
        imdbId: String?,
        seasonCount: Int?,
    ): SeriesCatalog? {
        if (source == StreamSource.AUTO) {
            return resolveSeries(title, year, imdbId, seasonCount)
        }
        // Respect the user's enabled set: a source disabled in Settings is skipped entirely.
        if (source !in sourceSettings.enabledSources.first()) return null
        // Bypass the AUTO cache so a pinned source doesn't get another source's URLs.
        val episodes = when (source) {
            StreamSource.CIRCLE_FTP -> {
                if (!sourceHealth.circleFtpEnabled.first()) emptyList()
                else runCatchingSuspend {
                    withTimeoutOrNull(API_TIMEOUT_MS.milliseconds) {
                        circleApi.resolveSeries(title, year, null)
                    }
                }.getOrNull().orEmpty()
            }
            StreamSource.ROAR_ZONE -> {
                if (!sourceHealth.roarZoneEnabled.first()) emptyList()
                else runCatchingSuspend {
                    withTimeoutOrNull(API_TIMEOUT_MS.milliseconds) {
                        roarZone.resolveSeries(imdbId, title, seasonCount, null)
                    }
                }.getOrNull().orEmpty()
            }
            else -> emptyList() // HdHub has no browse endpoint; resolves per episode at play time.
        }

        if (episodes.isEmpty()) {
            Log.w(TAG, "No episodes from ${source.name} for series '$title'")
            return null
        }
        val catalog = buildCatalog(title, episodes)
        return catalog
    }

    private fun EpisodeStream.isValidPlacement(): Boolean =
        (season != null) && (season > 0) && (score > 0)

    private fun EpisodeStream.requiredSeason(): Int = season!!

    private fun buildCatalog(title: String, episodes: List<EpisodeStream>): SeriesCatalog {
        val validEpisodes = episodes.filter { it.isValidPlacement() }
        if (validEpisodes.isEmpty()) {
            return SeriesCatalog(title = title, seasons = emptyMap(), bestGuess = null)
        }

        val groupedBySeason = validEpisodes.groupBy { it.requiredSeason() }
        val seasonsMap = LinkedHashMap<Int, SeriesSeason>()

        for ((seasonNum, seasonStreams) in groupedBySeason.entries.sortedBy { (key, _) -> key }) {
            val groupedByEpisode = seasonStreams.groupBy(EpisodeStream::episode)
            val cleanEpisodes = ArrayList<EpisodeStream>()

            for ((_, epStreams) in groupedByEpisode.entries.sortedBy { (key, _) -> key ?: Int.MAX_VALUE }) {
                val bestStream = epStreams.maxByOrNull(EpisodeStream::score) ?: continue
                val displayTitle = formatEpisodeDisplayTitle(bestStream.title, bestStream.episode ?: 0)
                cleanEpisodes.add(bestStream.copy(title = displayTitle))
            }

            seasonsMap[seasonNum] = SeriesSeason(number = seasonNum, episodes = cleanEpisodes)
        }

        val bestGuess = seasonsMap.values.firstOrNull()?.episodes?.firstOrNull()
        return SeriesCatalog(title = title, seasons = seasonsMap, bestGuess = bestGuess)
    }

    private fun formatEpisodeDisplayTitle(rawFileName: String, episode: Int): String {
        val epNumStr = "Episode $episode"

        val titlePatterns = listOf(
            Regex("""(?i)\b(?:episode|ep)\.?\s*\d{1,3}\s*[-:–—\s]+\s*([A-Za-z0-9][^\-]*?)\s*$"""),
            Regex("""(?i)-\s*\d{1,3}[.\s-]+\s*([A-Za-z0-9][^\-]*?)\s*$"""),
            Regex("""(?i)\b(?:episode|ep)\.?\s*\d{1,3}\s*[-:–—\s]+\s*(.*)$"""),
        )
        for (pattern in titlePatterns) {
            val m = pattern.find(rawFileName) ?: continue
            var title = m.groupValues[1].trim()
            title = title.replace(Regex("""(?i)\b(1080p|720p|480p|bluray|hdtv|web[- ]?dl|webrip|dvdrip|h264|x264|x265|hevc|10bit|ddp?5\.1|aac|ac3|dts|atmos|ingot|nodlabs|rovers|shortbrehd|hd4u|cinefeel|deflate|smurf|ggwp|b2b|caffeine|imagine|mkv|mp4|avi|webm)\b"""), "")
                .replace(Regex("""\s+"""), " ").trim(' ', '-', '_', '.', ',')
            if (title.length >= 3) {
                return "$epNumStr - ${title.replaceFirstChar { it.uppercase() }}"
            }
        }

        return epNumStr
    }

    private inline fun <T> runCatchingSuspend(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private class MemoryCache<K, V>(private val capacity: Int, private val ttlMs: Long) {
        private val map = object : LinkedHashMap<K, Pair<Long, V>>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Pair<Long, V>>?): Boolean =
                size > capacity
        }

        @Synchronized
        fun get(key: K): V? {
            val (timestamp, value) = map[key] ?: return null
            if ((System.currentTimeMillis() - timestamp) > ttlMs) {
                map.remove(key)
                return null
            }
            return value
        }

        @Synchronized
        fun put(key: K, value: V) {
            map[key] = System.currentTimeMillis() to value
        }
    }

    companion object {
        private const val TAG = "SeriesResolver"
        private const val API_TIMEOUT_MS = 15_000L
        private const val CATALOG_TTL_MS = 600_000L
    }
}
