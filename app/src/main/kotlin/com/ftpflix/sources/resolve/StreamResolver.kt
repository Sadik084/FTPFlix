package com.ftpflix.sources.resolve

import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.ftpflix.core.config.SourceHealthStore
import com.ftpflix.core.config.SourceSettingsStore
import com.ftpflix.data.local.ResolvedStreamDao
import com.ftpflix.data.local.ResolvedStreamEntity
import com.ftpflix.domain.parser.TitleNormalizer
import com.ftpflix.sources.HttpProbe
import com.ftpflix.sources.circleapi.CircleFtpResolver
import com.ftpflix.sources.hdhub.HdHubResolver
import com.ftpflix.sources.roarzone.RoarZoneApi
import com.ftpflix.sources.roarzone.RoarZoneResolver
import com.ftpflix.sources.hyper.HyperApi
import com.ftpflix.sources.hyper.HyperResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

data class ResolvedStream(
    val url: String,
    val host: String,
    val sizeDisplay: String,
    val headers: Map<String, String> = emptyMap(),
)

// Movie stream resolution in user-defined order, with HEAD-validated caching.
@UnstableApi
@Singleton
class StreamResolver @Inject constructor(
    private val circleApi: CircleFtpResolver,
    private val roarZone: RoarZoneResolver,
    private val hdhub: HdHubResolver,
    private val hyper: HyperResolver,
    private val sourceHealth: SourceHealthStore,
    private val sourceSettings: SourceSettingsStore,
    private val httpProbe: HttpProbe,
    private val resolvedStreamDao: ResolvedStreamDao,
    private val json: Json,
) {
    private val l1ResolveCache = MemoryCache<String, ResolvedStream>(64, L1_RESOLVE_TTL_MS)
    private val negativeCache = ConcurrentHashMap<String, Long>()

    suspend fun resolve(
        title: String,
        year: Int?,
        imdbId: String? = null,
        tmdbId: String? = null,
        force: Boolean = false,
        source: StreamSource = StreamSource.AUTO,
        preferredHeight: Int? = null,
    ): ResolvedStream? {
        val startedAt = System.currentTimeMillis()
        val baseKey = TitleNormalizer.key(title, year)
        // Per-source cache slot so a pinned source/quality doesn't collide with AUTO.
        val cacheKey =
            if (source == StreamSource.AUTO && preferredHeight == null) baseKey
            else "$baseKey|${source.name}|${preferredHeight ?: "best"}"
        Log.d(TAG, "Resolving stream for title='$title', year=$year, key='$cacheKey', source=$source, force=$force")

        if (!force) {
            l1ResolveCache.get(cacheKey)?.let {
                Log.d(TAG, "L1 cache hit for '$title' (${System.currentTimeMillis() - startedAt} ms)")
                return it
            }
            // Room cache for plain AUTO only.
            if (source == StreamSource.AUTO && preferredHeight == null) {
                val entity = resolvedStreamDao.get(baseKey)
                // RoarZone URLs embed a ~30 min token; skip if the host is disabled.
                val ttl = if (entity?.host == RoarZoneApi.HOST_LABEL) ROARZONE_TTL_MS else RESOLVED_TTL_MS
                val fresh = (entity != null) && ((System.currentTimeMillis() - entity.resolvedAt) <= ttl)
                val hostEnabled = (entity == null) || isHostEnabled(entity.host)
                val cachedHeaders = decodeHeaders(entity?.headers)
                val alive = entity == null || httpProbe.headOk(entity.url, cachedHeaders)
                if ((entity != null) && fresh && hostEnabled && alive) {
                    Log.d(TAG, "Room cache hit for '$title' (${System.currentTimeMillis() - startedAt} ms)")
                    val cached = ResolvedStream(entity.url, entity.host, entity.sizeDisplay, cachedHeaders)
                    l1ResolveCache.put(cacheKey, cached)
                    return cached
                }
                if (entity != null) Log.d(TAG, "Cached URL stale/dead/disabled for '$title'; re-resolving")
            }
        }

        // Forced resolves always hit the API, skipping the negative cache.
        if (!force) {
            negativeCache[cacheKey]?.let { ts ->
                if ((System.currentTimeMillis() - ts) <= NEGATIVE_TTL_MS) {
                    Log.d(TAG, "Negative cache hit for '$title'")
                    return null
                }
            }
        }

        val stream = resolveFromSources(title, year, imdbId, tmdbId, source, preferredHeight)

        if (stream == null) {
            Log.w(TAG, "FAILED to resolve stream for '$title' [${source.name}] (${System.currentTimeMillis() - startedAt} ms)")
            negativeCache[cacheKey] = System.currentTimeMillis()
            return null
        }

        Log.d(TAG, "SUCCESS resolved '$title' [${source.name}]: ${stream.url} (${System.currentTimeMillis() - startedAt} ms)")
        l1ResolveCache.put(cacheKey, stream)
        // Quality picks are not persisted.
        if (source == StreamSource.AUTO && preferredHeight == null) {
            resolvedStreamDao.upsert(
                ResolvedStreamEntity(
                    key = baseKey,
                    title = title,
                    year = year,
                    url = stream.url,
                    host = stream.host,
                    sizeDisplay = stream.sizeDisplay,
                    resolvedAt = System.currentTimeMillis(),
                    headers = encodeHeaders(stream.headers),
                ),
            )
        }
        return stream
    }

    /** Next best playable stream after [exclude] URLs failed to decode. */
    suspend fun resolveAlternate(
        title: String,
        year: Int?,
        imdbId: String? = null,
        tmdbId: String? = null,
        exclude: Set<String>,
    ): ResolvedStream? {
        // Smaller encodes are often real H.264 even when the 1080p one is mislabeled HEVC.
        val lower = resolve(title, year, imdbId, tmdbId, force = true, preferredHeight = 720)
        if (lower != null && lower.url !in exclude && httpProbe.headOk(lower.url)) {
            Log.d(TAG, "resolveAlternate 720p '$title' -> ${lower.url}")
            return lower
        }
        val order = sourceSettings.sourceOrder.first()
        val enabled = sourceSettings.enabledSources.first()
        val active = order.filter { it in enabled }
        val results = coroutineScope {
            active.map { s ->
                async {
                    val hit = when (s) {
                        StreamSource.CIRCLE_FTP -> tryCircle(title, year, null)
                        StreamSource.ROAR_ZONE -> tryRoar(imdbId, title, year, null)
                        StreamSource.HD_HUB -> tryHdHub(imdbId, null)
                        StreamSource.HYPER -> tryHyper(imdbId, null)
                        StreamSource.AUTO -> null
                    }
                    s to hit
                }
            }.awaitAll()
        }
        for ((s, hit) in results) {
            if (hit != null && hit.url !in exclude && httpProbe.headOk(hit.url)) {
                Log.d(TAG, "resolveAlternate ${s.name} '$title' -> ${hit.url}")
                return hit
            }
        }
        return null
    }

    // Resolve enabled sources in parallel; wait for all, then pick the first hit in user order.
    private suspend fun resolveFromSources(
        title: String,
        year: Int?,
        imdbId: String?,
        tmdbId: String?,
        source: StreamSource,
        preferredHeight: Int?,
    ): ResolvedStream? {
        // Skip sources disabled in Settings.
        if (source != StreamSource.AUTO && source !in sourceSettings.enabledSources.first()) return null
        when (source) {
            StreamSource.AUTO -> {
                val order = sourceSettings.sourceOrder.first()
                val enabled = sourceSettings.enabledSources.first()
                val active = order.filter { it in enabled }
                if (active.isEmpty()) return null
                val results = coroutineScope {
                    active.map { s ->
                        async { s to runSource(s, title, year, imdbId, preferredHeight) }
                    }.awaitAll()
                }
                for ((s, hit) in results) {
                    if (hit != null) {
                        Log.d(TAG, "${s.name} resolved '$title'")
                        return hit
                    }
                }
            }
            StreamSource.CIRCLE_FTP -> tryCircle(title, year, preferredHeight)?.let { return it }
            StreamSource.ROAR_ZONE -> tryRoar(imdbId, title, year, preferredHeight)?.let { return it }
            StreamSource.HD_HUB -> tryHdHub(imdbId, preferredHeight)?.let { return it }
            StreamSource.HYPER -> tryHyper(imdbId, preferredHeight)?.let { return it }
        }
        return null
    }

    private suspend fun runSource(
        s: StreamSource,
        title: String,
        year: Int?,
        imdbId: String?,
        preferredHeight: Int?,
    ): ResolvedStream? = when (s) {
        StreamSource.CIRCLE_FTP -> tryCircle(title, year, preferredHeight)
        StreamSource.ROAR_ZONE -> tryRoar(imdbId, title, year, preferredHeight)
        StreamSource.HD_HUB -> tryHdHub(imdbId, preferredHeight)
        StreamSource.HYPER -> tryHyper(imdbId, preferredHeight)
        StreamSource.AUTO -> null
    }

    private suspend fun tryCircle(title: String, year: Int?, preferredHeight: Int?): ResolvedStream? =
        if (sourceHealth.circleFtpEnabled.first()) {
            runCatchingSuspend {
                withTimeoutOrNull(API_TIMEOUT_MS.milliseconds) {
                    circleApi.resolveMovie(title, year, preferredHeight)
                }
            }.getOrNull()
        } else null

    private suspend fun tryRoar(
        imdbId: String?,
        title: String,
        year: Int?,
        preferredHeight: Int?
    ): ResolvedStream? =
        if (sourceHealth.roarZoneEnabled.first()) {
            runCatchingSuspend {
                withTimeoutOrNull(API_TIMEOUT_MS.milliseconds) {
                    roarZone.resolveMovie(imdbId, title, year, preferredHeight)
                }
            }.getOrNull()
        } else null

    private suspend fun tryHdHub(imdbId: String?, preferredHeight: Int?): ResolvedStream? =
        runCatchingSuspend {
            withTimeoutOrNull(API_TIMEOUT_MS.milliseconds) {
                hdhub.resolveMovie(imdbId, preferredHeight)
            }
        }.getOrNull()

    private suspend fun tryHyper(imdbId: String?, preferredHeight: Int?): ResolvedStream? =
        runCatchingSuspend {
            withTimeoutOrNull(API_TIMEOUT_MS.milliseconds) {
                hyper.resolveMovie(imdbId, preferredHeight)
            }
        }.getOrNull()

    private suspend fun isHostEnabled(host: String): Boolean = when (host) {
        RoarZoneApi.HOST_LABEL -> sourceHealth.roarZoneEnabled.first()
        // Hyper results have no health gate; never fall through to the CircleFTP check.
        HyperApi.HOST_LABEL -> true
        else -> sourceHealth.circleFtpEnabled.first()
    }

    private inline fun <T> runCatchingSuspend(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun encodeHeaders(headers: Map<String, String>): String? =
        if (headers.isEmpty()) null else runCatching { json.encodeToString(headers) }.getOrNull()

    private fun decodeHeaders(raw: String?): Map<String, String> =
        raw?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: emptyMap()

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
        private const val TAG = "StreamResolver"
        private const val API_TIMEOUT_MS = 15_000L
        private const val NEGATIVE_TTL_MS = 5 * 60_000L
        private const val RESOLVED_TTL_MS = 7 * 24 * 60 * 60_000L
        /** RoarZone stream URLs embed the Emby access token; keep them well under its life. */
        private const val ROARZONE_TTL_MS = 25 * 60_000L
        private const val L1_RESOLVE_TTL_MS = 60_000L
    }
}
