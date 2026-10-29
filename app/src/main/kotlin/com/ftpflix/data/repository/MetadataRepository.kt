package com.ftpflix.data.repository

import com.ftpflix.BuildConfig
import com.ftpflix.data.local.CardDao
import com.ftpflix.data.local.MetadataDao
import com.ftpflix.data.local.MetadataEntity
import com.ftpflix.core.config.AgeRatingStore
import com.ftpflix.data.model.MovieMetadata
import com.ftpflix.data.remote.TmdbContentRatings
import com.ftpflix.data.remote.TmdbEpisode
import com.ftpflix.data.remote.TmdbMovie
import com.ftpflix.data.remote.TmdbReleaseDates
import com.ftpflix.data.remote.TmdbService
import com.ftpflix.data.remote.TmdbTv
import com.ftpflix.domain.parser.ParsedName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class TitleCandidate(
    val imdbId: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val actors: String?,
)

// TMDB metadata provider; Room-cached, negatively cached on failure.
@Singleton
class MetadataRepository @Inject constructor(
    private val tmdb: TmdbService,
    private val cardDao: CardDao,
    private val metadataDao: MetadataDao,
    private val breaker: ProviderCircuitBreaker,
    private val ageRatings: AgeRatingStore,
) {
    private val apiKey: String = BuildConfig.TMDB_API_KEY
    private val idPattern = Regex("""^(movie|tv):(\d+)$""")

    private val seasonNamesCache = ConcurrentHashMap<Long, Map<Int, Map<Int, String>>>()

    // Real episode names per season, cached in memory; failures fall back to "Episode N".
    suspend fun seasonEpisodeNames(
        tvId: Long,
        seasons: List<Int>,
    ): Map<Int, Map<Int, String>> {
        val sortedSeasons = seasons.toSortedSet()
        seasonNamesCache[tvId]?.let { cached ->
            if (cached.keys.containsAll(sortedSeasons)) return cached
        }
        val fetched = coroutineScope {
            sortedSeasons.map { season ->
                async(Dispatchers.IO) { season to fetchSeasonNames(tvId, season) }
            }.awaitAll().toMap()
        }
        val merged = (seasonNamesCache[tvId] ?: emptyMap()) + fetched.filterValues { it.isNotEmpty() }
        if (merged.isNotEmpty()) seasonNamesCache[tvId] = merged
        return fetched.filterValues { it.isNotEmpty() }
    }

    private fun TmdbEpisode.hasRealName(): Boolean = (episodeNumber > 0) && name.isNotBlank()

    private suspend fun fetchSeasonNames(tvId: Long, season: Int): Map<Int, String> =
        try {
            withTimeout(12.seconds) {
                tmdb.tvSeasonDetails(tvId, season, apiKey).episodes.asSequence()
                    .filter { it.hasRealName() }
                    .associateBy(TmdbEpisode::episodeNumber, TmdbEpisode::name)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }

    // Full details for a stored title; used when opening it.
    suspend fun details(mediaId: String): MovieMetadata? {
        val m = idPattern.matchEntire(mediaId) ?: return null
        val kind = m.groupValues[1]
        val id = m.groupValues[2].toLongOrNull() ?: return null

        // Rows without an IMDb id are stale; refetch to backfill it.
        val cachedModel = metadataDao.get("tmdb", mediaId)
            ?.takeIf { !it.isExpired() && (it.imdbId != null) }
            ?.toModel()
        if (cachedModel != null) {
            ageRatings.put(mediaId, cachedModel.ageRating)
            return cachedModel
        }

        val windowStart = System.currentTimeMillis() - NEGATIVE_CACHE_MS
        if (metadataDao.isRecentlyFailed("tmdb", mediaId, windowStart)) {
            metadataDao.get("tmdb", mediaId)?.let { return it.toModel() }
        }

        var result = if (!breaker.allow("tmdb")) null else runCatching {
            withTimeout(12.seconds) {
                if (kind == "tv") tmdb.tvDetails(id, apiKey)
                else tmdb.movieDetails(id, apiKey)
            }
        }.onSuccess { breaker.success("tmdb") }
            .onFailure { breaker.failure("tmdb") }
            .getOrNull()
            ?.let { toMetadata(it, mediaId) }

        // Backfill a missing IMDb id so IMDb-keyed sources can resolve.
        if (result?.imdbId == null) {
            val ext = runCatching {
                withTimeout(8.seconds) {
                    if (kind == "tv") tmdb.tvExternalIds(id, apiKey)
                    else tmdb.movieExternalIds(id, apiKey)
                }
            }.getOrNull()
            if (ext?.imdbId?.isNotBlank() == true) {
                result = result?.copy(imdbId = ext.imdbId)
            }
        }

        return if (result != null) {
            metadataDao.upsert(result.toEntity(mediaId))
            ageRatings.put(mediaId, result.ageRating)
            result
        } else {
            metadataDao.markFailed("tmdb", mediaId, System.currentTimeMillis())
            val staleModel = metadataDao.get("tmdb", mediaId)?.toModel()
            staleModel?.let { ageRatings.put(mediaId, it.ageRating) }
            staleModel
        }
    }

    // Slim certification lookup for poster tags (release_dates / content_ratings only).
    suspend fun ageRatingFor(mediaId: String): String? {
        val m = idPattern.matchEntire(mediaId) ?: return null
        val kind = m.groupValues[1]
        val id = m.groupValues[2].toLongOrNull() ?: return null

        metadataDao.get("tmdb", mediaId)
            ?.ageRating
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val rating = try {
            if (kind == "tv") {
                withTimeout(8.seconds) { tmdb.tvContentRatings(id, apiKey) }.rating()
            } else {
                withTimeout(8.seconds) { tmdb.movieReleaseDates(id, apiKey) }.certification()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            breaker.failure("tmdb")
            null
        }

        if (!rating.isNullOrBlank()) {
            breaker.success("tmdb")
            ageRatings.put(mediaId, rating)
            metadataDao.updateAgeRating("tmdb", mediaId, rating)
        }
        return rating
    }

    suspend fun candidatesFor(parsed: ParsedName): List<TitleCandidate> {
        val out = mutableListOf<TitleCandidate>()
        runCatching {
            out += tmdb.searchMovie(apiKey, parsed.title, parsed.year).results.map { it.toCandidate("movie") }
        }
        // Always include TV results too so the Series filter works.
        runCatching {
            out += tmdb.searchTv(apiKey, parsed.title, parsed.year).results.map { it.toCandidate("tv") }
        }
        return out.distinctBy { it.imdbId }
    }

    suspend fun clearCache() {
        cardDao.clear()
        metadataDao.clear()
    }

    private fun TmdbMovie.toCandidate(kind: String) = TitleCandidate(
        imdbId = "$kind:$id",
        title = title,
        year = releaseDate?.take(4)?.toIntOrNull(),
        posterUrl = posterPath?.let { imageUrl(it, "w500") },
        actors = null,
    )

    private fun TmdbTv.toCandidate(kind: String) = TitleCandidate(
        imdbId = "$kind:$id",
        title = name,
        year = firstAirDate?.take(4)?.toIntOrNull(),
        posterUrl = posterPath?.let { imageUrl(it, "w500") },
        actors = null,
    )

    private fun toMetadata(any: Any, mediaId: String): MovieMetadata? = when (any) {
        is com.ftpflix.data.remote.TmdbMovieDetails -> MovieMetadata(
            id = mediaId,
            title = any.title,
            year = any.releaseDate?.take(4)?.toIntOrNull(),
            posterUrl = any.posterPath?.let { imageUrl(it, "w500") },
            backgroundUrl = any.backdropPath?.let { imageUrl(it, "w1280") },
            description = any.overview.takeIf { it.isNotBlank() },
            runtimeMinutes = any.runtime,
            genres = any.genres.map { it.name },
            cast = any.credits?.cast?.asSequence()?.take(12)?.map { it.name }?.toList() ?: emptyList(),
            imdbRating = any.voteAverage.takeIf { it > 0 },
            kind = "movie",
            imdbId = (any.externalIds?.imdbId ?: any.imdbId)?.takeIf { it.isNotBlank() },
            ageRating = any.releaseDates.certification(),
        )

        is com.ftpflix.data.remote.TmdbTvDetails -> MovieMetadata(
            id = mediaId,
            title = any.name,
            year = any.firstAirDate?.take(4)?.toIntOrNull(),
            posterUrl = any.posterPath?.let { imageUrl(it, "w500") },
            backgroundUrl = any.backdropPath?.let { imageUrl(it, "w1280") },
            description = any.overview.takeIf { it.isNotBlank() },
            runtimeMinutes = any.numberOfEpisodes,
            genres = any.genres.map { it.name },
            cast = any.credits?.cast?.asSequence()?.take(12)?.map { it.name }?.toList() ?: emptyList(),
            imdbRating = any.voteAverage.takeIf { it > 0 },
            kind = "series",
            imdbId = (any.externalIds?.imdbId ?: any.imdbId)?.takeIf { it.isNotBlank() },
            numberOfSeasons = any.numberOfSeasons,
            ageRating = any.contentRatings.rating(),
        )

        else -> null
    }

    private fun imageUrl(path: String, size: String): String =
        "https://image.tmdb.org/t/p/$size$path"
}

private val PREFERRED_RATING_COUNTRIES = listOf("US", "GB", "CA", "AU", "DE", "FR", "NZ", "IE")

private fun validCert(value: String): Boolean =
    value.isNotBlank() && (value !in setOf("0", "-"))

/** Certification from preferred countries first, else any non-blank one; null when unrated. */
private fun TmdbReleaseDates?.certification(): String? {
    val results = this?.results.orEmpty()
    val byCountry = results.associateBy { it.country }
    PREFERRED_RATING_COUNTRIES.forEach { cc ->
        byCountry[cc]?.releaseDates?.asSequence()
            ?.map { it.certification }
            ?.firstOrNull { validCert(it) }
            ?.let { return it }
    }
    return results.asSequence()
        .flatMap { it.releaseDates.asSequence() }
        .map { it.certification }
        .firstOrNull { validCert(it) }
}

private fun TmdbContentRatings?.rating(): String? {
    val results = this?.results.orEmpty()
    val byCountry = results.associateBy { it.country }
    PREFERRED_RATING_COUNTRIES.forEach { cc ->
        byCountry[cc]?.rating?.takeIf { validCert(it) }?.let { return it }
    }
    return results.asSequence().map { it.rating }.firstOrNull { validCert(it) }
}

@Singleton
class ProviderCircuitBreaker @Inject constructor() {
    private data class State(var failures: Int = 0, var pausedUntil: Long = 0)
    private val states = ConcurrentHashMap<String, State>()

    fun allow(provider: String): Boolean =
        System.currentTimeMillis() >= (states[provider]?.pausedUntil ?: 0)

    fun success(provider: String) {
        states[provider]?.failures = 0
    }

    fun failure(provider: String) {
        val s = states.getOrPut(provider) { State() }
        if (++s.failures >= 3) {
            s.pausedUntil = System.currentTimeMillis() + (10 * 60_000)
            s.failures = 0
        }
    }
}

private const val CARD_TTL_MS = 30L * 24 * 60 * 60 * 1000
private const val NEGATIVE_CACHE_MS = 6L * 60 * 60 * 1000

private fun MetadataEntity.isExpired(): Boolean =
    (System.currentTimeMillis() - fetchedAt) > CARD_TTL_MS

private fun MetadataEntity.toModel(): MovieMetadata = MovieMetadata(
    id = id,
    title = title,
    year = year,
    posterUrl = posterUrl,
    backgroundUrl = backgroundUrl,
    description = description,
    runtimeMinutes = runtimeMinutes,
    genres = genres.split(",").filter { it.isNotBlank() },
    cast = cast.split(",").filter { it.isNotBlank() },
    imdbRating = imdbRating,
    kind = kind,
    imdbId = imdbId,
    numberOfSeasons = numberOfSeasons,
    ageRating = ageRating,
)

private fun MovieMetadata.toEntity(mediaId: String): MetadataEntity = MetadataEntity(
    provider = "tmdb",
    id = mediaId,
    kind = kind,
    title = title,
    year = year,
    posterUrl = posterUrl,
    backgroundUrl = backgroundUrl,
    description = description,
    runtimeMinutes = runtimeMinutes,
    genres = genres.joinToString(","),
    cast = cast.joinToString(","),
    imdbRating = imdbRating,
    fetchedAt = System.currentTimeMillis(),
    failedAt = null,
    imdbId = imdbId,
    numberOfSeasons = numberOfSeasons,
    ageRating = ageRating,
)
