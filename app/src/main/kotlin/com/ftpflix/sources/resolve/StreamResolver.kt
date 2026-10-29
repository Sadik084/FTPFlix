package com.ftpflix.sources.resolve

import android.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

data class ResolvedStream(
    val url: String,
    val host: String,
    val sizeDisplay: String,
)

// Movie stream resolution in user-defined order, with HEAD-validated caching.
@Singleton
class StreamResolver @Inject constructor(
    private val circleApi: CircleFtpResolver,
    private val roarZone: RoarZoneResolver,
    private val hdhub: HdHubResolver,
    private val sourceHealth: SourceHealthStore,
    private val sourceSettings: SourceSettingsStore,
    private val httpProbe: HttpProbe,
    private val resolvedStreamDao: ResolvedStreamDao,
) {
    private val l1ResolveCache = MemoryCache<String, ResolvedStream>(64, L1_RESOLVE_TTL_MS)
    private val negativeCache = ConcurrentHashMap<String, Long>()

    suspend fun resolve(
        title: String,
        year: Int?,
        imdbId: String? = null,
        force: Boolean = false,
        source: StreamSource = StreamSource.AUTO,
        preferredHeight: Int? = null,
    ): ResolvedStream? {
        val startedAt = System.currentTimeMillis()
        val baseKey = TitleNormalizer.key(title, year)
        // Per-source cache slot so a pinned source/quality doesn't collide with AUTO.
        val cacheKey =
            if (source == StreamSource.AUTO) baseKey
            else "$baseKey|${source.name}|${preferredHeight ?: "best"}"
        Log.d(TAG, "Resolving stream for title='$title', year=$year, key='$cacheKey', source=$source, force=$force")

        if (!force) {
            l1ResolveCache.get(cacheKey)?.let {
                Log.d(TAG, "L1 cache hit for '$title' (${System.currentTimeMillis() - startedAt} ms)")
                return it
            }
            // Room cache only for AUTO (7-day TTL); pinned sources stay in-memory.
            if (source == StreamSource.AUTO) {
                val entity = resolvedStreamDao.get(baseKey)
                // RoarZone URLs embed a ~30 min token; skip if the host is disabled.
                val ttl = if (entity?.host == RoarZoneApi.HOST_LABEL) ROARZONE_TTL_MS else RESOLVED_TTL_MS
                val fresh = (entity != null) && ((System.currentTimeMillis() - entity.resolvedAt) <= ttl)
                val hostEnabled = (entity == null) || isHostEnabled(entity.host)
                if ((entity != null) && fresh && hostEnabled && httpProbe.headOk(entity.url)) {
                    Log.d(TAG, "Room cache hit for '$title' (${System.currentTimeMillis() - startedAt} ms)")
                    val cached = ResolvedStream(entity.url, entity.host, entity.sizeDisplay)
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

        val stream = resolveFromSources(title, year, imdbId, source, preferredHeight)

        if (stream == null) {
            Log.w(TAG, "FAILED to resolve stream for '$title' [${source.name}] (${System.currentTimeMillis() - startedAt} ms)")
            negativeCache[cacheKey] = System.currentTimeMillis()
            return null
        }

        Log.d(TAG, "SUCCESS resolved '$title' [${source.name}]: ${stream.url} (${System.currentTimeMillis() - startedAt} ms)")
        l1ResolveCache.put(cacheKey, stream)
        if (source == StreamSource.AUTO) {
            resolvedStreamDao.upsert(
                ResolvedStreamEntity(
                    key = baseKey,
                    title = title,
                    year = year,
                    url = stream.url,
                    host = stream.host,
                    sizeDisplay = stream.sizeDisplay,
                    resolvedAt = System.currentTimeMillis(),
                ),
            )
        }
        return stream
    }

    // Sequential user-ordered cascade: enabled sources tried until one hits.
    private suspend fun resolveFromSources(
        title: String,
        year: Int?,
        imdbId: String?,
        source: StreamSource,
        preferredHeight: Int?,
    ): ResolvedStream? {
        when (source) {
            StreamSource.AUTO -> {
                val order = sourceSettings.sourceOrder.first()
                val enabled = sourceSettings.enabledSources.first()
                for (s in order) {
                    if (s !in enabled) continue
                    val hit = when (s) {
                        StreamSource.CIRCLE_FTP -> tryCircle(title, year, preferredHeight)
                        StreamSource.ROAR_ZONE -> tryRoar(imdbId, title, year, preferredHeight)
                        StreamSource.HD_HUB -> tryHdHub(imdbId, preferredHeight)
                        StreamSource.AUTO -> null
                    }
                    hit?.let { return it }
                }
            }
            StreamSource.CIRCLE_FTP -> tryCircle(title, year, preferredHeight)?.let { return it }
            StreamSource.ROAR_ZONE -> tryRoar(imdbId, title, year, preferredHeight)?.let { return it }
            StreamSource.HD_HUB -> tryHdHub(imdbId, preferredHeight)?.let { return it }
        }
        return null
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

    private suspend fun isHostEnabled(host: String): Boolean = when (host) {
        RoarZoneApi.HOST_LABEL -> sourceHealth.roarZoneEnabled.first()
        else -> sourceHealth.circleFtpEnabled.first()
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
        private const val TAG = "StreamResolver"
        private const val API_TIMEOUT_MS = 15_000L
        private const val NEGATIVE_TTL_MS = 30 * 60_000L
        private const val RESOLVED_TTL_MS = 7 * 24 * 60 * 60_000L
        /** RoarZone stream URLs embed the Emby access token; keep them well under its life. */
        private const val ROARZONE_TTL_MS = 25 * 60_000L
        private const val L1_RESOLVE_TTL_MS = 60_000L
    }
}
