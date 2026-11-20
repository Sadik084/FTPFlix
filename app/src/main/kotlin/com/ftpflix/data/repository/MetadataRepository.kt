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
import com.ftpflix.ui.search.SearchFilters
import com.ftpflix.ui.search.SearchSort
import com.ftpflix.ui.search.SearchFilter
import com.ftpflix.ui.search.SearchCertifications
import com.ftpflix.data.remote.TmdbGenre
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    val genreIds: List<Int> = emptyList(),
    val voteAverage: Double = 0.0,
    val voteCount: Int = 0,
    val originalLanguage: String = "",
    val popularity: Double = 0.0,
    /** Exact release / first-air date (ISO). */
    val releaseDate: String? = null,
)

// Episode name + air date from TMDB.
data class EpisodeMeta(
    val name: String?,
    val airDate: String?,
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

    private val seasonMetaCache = ConcurrentHashMap<Long, Map<Int, Map<Int, EpisodeMeta>>>()

    private val seasonCountsCache = ConcurrentHashMap<String, Map<Int, Int>>()

    // TMDB season -> episode count; specials and empty stubs excluded.
    suspend fun seasonEpisodeCounts(mediaId: String): Map<Int, Int> {
        seasonCountsCache[mediaId]?.let { return it }
        val m = idPattern.matchEntire(mediaId) ?: return emptyMap()
        if (m.groupValues[1] != "tv") return emptyMap()
        val id = m.groupValues[2].toLongOrNull() ?: return emptyMap()
        val counts = runCatching {
            withTimeout(12.seconds) {
                tmdb.tvSeasonList(id, apiKey).seasons
                    .filter { it.seasonNumber > 0 && it.episodeCount > 0 }
                    .associate { it.seasonNumber to it.episodeCount }
            }
        }.getOrDefault(emptyMap())
        if (counts.isNotEmpty()) seasonCountsCache[mediaId] = counts
        return counts
    }

    // Episode names + air dates per season; failures fall back to "Episode N".
    suspend fun seasonEpisodeMeta(
        tvId: Long,
        seasons: List<Int>,
    ): Map<Int, Map<Int, EpisodeMeta>> {
        val sortedSeasons = seasons.toSortedSet()
        seasonMetaCache[tvId]?.let { cached ->
            if (cached.keys.containsAll(sortedSeasons)) return cached
        }
        val fetched = coroutineScope {
            sortedSeasons.map { season ->
                async(Dispatchers.IO) { season to fetchSeasonMeta(tvId, season) }
            }.awaitAll().toMap()
        }
        val merged = (seasonMetaCache[tvId] ?: emptyMap()) + fetched.filterValues { it.isNotEmpty() }
        if (merged.isNotEmpty()) seasonMetaCache[tvId] = merged
        return fetched.filterValues { it.isNotEmpty() }
    }

    private fun TmdbEpisode.hasRealName(): Boolean = (episodeNumber > 0) && name.isNotBlank()

    private suspend fun fetchSeasonMeta(tvId: Long, season: Int): Map<Int, EpisodeMeta> =
        try {
            withTimeout(12.seconds) {
                tmdb.tvSeasonDetails(tvId, season, apiKey).episodes.asSequence()
                    .filter { it.hasRealName() }
                    .associateBy(TmdbEpisode::episodeNumber) { EpisodeMeta(it.name, it.airDate) }
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

    /** Certification per media id + country; avoids refetching on every search. */
    private val certificationCache = ConcurrentHashMap<String, String>()

    /**
     * Certification for a specific country, falling back to the preferred-country value.
     * Used to filter search results by age rating.
     */
    suspend fun certificationFor(mediaId: String, country: String): String? {
        val cacheKey = "$mediaId|$country"
        certificationCache[cacheKey]?.let { return it }
        if (country == "US") {
            metadataDao.get("tmdb", mediaId)?.ageRating?.takeIf { it.isNotBlank() }?.let {
                certificationCache[cacheKey] = it
                return it
            }
        }
        val m = idPattern.matchEntire(mediaId) ?: return null
        val id = m.groupValues[2].toLongOrNull() ?: return null
        val kind = m.groupValues[1]
        val rating = try {
            withTimeout(8.seconds) {
                if (kind == "tv") tmdb.tvContentRatings(id, apiKey).forCountry(country)
                else tmdb.movieReleaseDates(id, apiKey).forCountry(country)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            breaker.failure("tmdb")
            null
        }
        if (!rating.isNullOrBlank()) {
            breaker.success("tmdb")
            certificationCache[cacheKey] = rating
        }
        return rating
    }

    private val genreCache = ConcurrentHashMap<String, List<TmdbGenre>>()

    suspend fun candidatesFor(parsed: ParsedName, filters: SearchFilters = SearchFilters.DEFAULT): List<TitleCandidate> {
        // If there's a text query, use search endpoint + client-side filtering
        if (parsed.title.isNotBlank()) {
            return searchCandidates(parsed, filters)
        }
        // No text query: use discover endpoint for server-side filtering
        return discoverCandidates(filters)
    }

    private suspend fun searchCandidates(parsed: ParsedName, filters: SearchFilters): List<TitleCandidate> {
        val kinds = when (filters.type) {
            SearchFilter.All -> listOf("movie", "tv")
            SearchFilter.Movies -> listOf("movie")
            SearchFilter.Series -> listOf("tv")
        }
        // Filters are applied client-side, so pull an extra page when they are active.
        val pages = if (filters.hasContentFilters) SEARCH_PAGES_FILTERED else 1
        val results = coroutineScope {
            kinds.flatMap { kind ->
                (1..pages).map { page ->
                    async(Dispatchers.IO) {
                        runCatching {
                            when (kind) {
                                "movie" -> tmdb.searchMovie(apiKey, parsed.title, parsed.year, page = page)
                                    .results.map { it.toCandidate("movie") }

                                else -> tmdb.searchTv(apiKey, parsed.title, parsed.year, page = page)
                                    .results.map { it.toCandidate("tv") }
                            }
                        }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll()
        }
        val pool = results.flatten().distinctBy { it.imdbId }
        val rated = applyCertification(pool, filters)
        // Rank by title similarity; the client applies the remaining filters.
        val query = parsed.title
        return rated.map { candidate ->
            candidate to com.ftpflix.util.TitleMatchingUtils.calculateSimilarityScore(query, candidate.title)
        }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /**
     * Keeps only candidates whose certification is one of the selected ratings.
     * TMDB's search endpoint cannot filter by certification, so it is resolved per title.
     */
    private suspend fun applyCertification(
        candidates: List<TitleCandidate>,
        filters: SearchFilters,
    ): List<TitleCandidate> {
        val wanted = filters.certification
        if (wanted.isEmpty() || candidates.isEmpty()) return candidates
        val country = filters.certificationCountry
        val scoped = candidates.take(MAX_CERT_LOOKUPS)
        return coroutineScope {
            val semaphore = Semaphore(CERT_CONCURRENCY)
            val ratings = scoped.map { candidate ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { certificationFor(candidate.imdbId, country) }
                }
            }.awaitAll()
            scoped.zip(ratings)
                .filter { (_, cert) -> cert != null && cert in wanted }
                .map { it.first }
        }
    }

    private suspend fun discoverCandidates(filters: SearchFilters): List<TitleCandidate> {
        val kinds = when (filters.type) {
            SearchFilter.All -> listOf("movie", "tv")
            SearchFilter.Movies -> listOf("movie")
            SearchFilter.Series -> listOf("tv")
        }
        val country = filters.certificationCountry
        val selected = filters.certification
        // A kind is only browsable when one of the selected ratings belongs to it.
        val browsableKinds = kinds.filter { kind ->
            selected.isEmpty() || certsFor(kind, country).any { it in selected }
        }

        val genreParam = filters.genres.joinToString(",").takeIf { it.isNotBlank() }
        val languageParam = filters.languages.joinToString(",").takeIf { it.isNotBlank() }
        val minRating = filters.minRating.takeIf { it > 0 }

        val yearGte = filters.yearFrom?.let { "$it-01-01" }
        val yearLte = filters.yearTo?.let { "$it-12-31" }

        val results = coroutineScope {
            browsableKinds.flatMap { kind ->
                val certs = if (selected.isEmpty()) {
                    listOf<String?>(null)
                } else {
                    certsFor(kind, country).filter { it in selected }.map { it as String? }
                }
                certs.map { cert ->
                    async(Dispatchers.IO) {
                        runCatching {
                            tmdb.discover(
                                kind = kind,
                                apiKey = apiKey,
                                sortBy = discoverSortFor(kind, filters.sortBy),
                                withGenres = genreParam,
                                voteAverageGte = minRating,
                                withOriginalLanguage = languageParam,
                                primaryReleaseDateGte = if (kind == "movie") yearGte else null,
                                primaryReleaseDateLte = if (kind == "movie") yearLte else null,
                                firstAirDateGte = if (kind == "tv") yearGte else null,
                                firstAirDateLte = if (kind == "tv") yearLte else null,
                                certification = cert,
                                certificationCountry = if (cert != null) country else null,
                            ).results.map { it.toCandidate(kind) }
                        }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll()
        }

        return results.flatten().distinctBy { it.imdbId }
    }

    /** Certification values that apply to a media kind in the given country. */
    private fun certsFor(kind: String, country: String): List<String> =
        if (kind == "movie") SearchCertifications.movieCerts(country)
        else SearchCertifications.tvCerts(country)

    /** TMDB sort keys differ between movies and TV. */
    private fun discoverSortFor(kind: String, sort: SearchSort): String = when (sort) {
        SearchSort.Popularity -> "popularity.desc"
        SearchSort.Rating -> "vote_average.desc"
        SearchSort.ReleaseDate -> if (kind == "movie") "primary_release_date.desc" else "first_air_date.desc"
        SearchSort.Title -> if (kind == "movie") "original_title.asc" else "original_name.asc"
        SearchSort.Relevance -> "popularity.desc"
    }

    /** Fetch genre lists from TMDB (cached) */
    suspend fun getMovieGenres(): List<TmdbGenre> {
        genreCache["movie"]?.let { return it }
        val genres = runCatching { tmdb.movieGenres(apiKey).genres }.getOrDefault(emptyList())
        if (genres.isNotEmpty()) genreCache["movie"] = genres
        return genres
    }

    suspend fun getTvGenres(): List<TmdbGenre> {
        genreCache["tv"]?.let { return it }
        val genres = runCatching { tmdb.tvGenres(apiKey).genres }.getOrDefault(emptyList())
        if (genres.isNotEmpty()) genreCache["tv"] = genres
        return genres
    }

    suspend fun getAllGenres(): List<TmdbGenre> {
        val movie = getMovieGenres()
        val tv = getTvGenres()
        return (movie + tv).distinctBy { it.id }
    }

    suspend fun clearCache() {
        cardDao.clear()
        metadataDao.clear()
    }

    // TMDB returns movie and TV payloads through one discover endpoint; TV rows carry
    // "name"/"first_air_date" instead of "title"/"release_date".
    private fun TmdbMovie.toCandidate(kind: String) = TitleCandidate(
        imdbId = "$kind:$id",
        title = title.ifBlank { name },
        year = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull(),
        posterUrl = posterPath?.let { imageUrl(it, "w500") },
        actors = null,
        genreIds = genreIds,
        voteAverage = voteAverage,
        voteCount = voteCount,
        originalLanguage = originalLanguage,
        popularity = popularity,
        releaseDate = releaseDate ?: firstAirDate,
    )

    private fun TmdbTv.toCandidate(kind: String) = TitleCandidate(
        imdbId = "$kind:$id",
        title = name,
        year = firstAirDate?.take(4)?.toIntOrNull(),
        posterUrl = posterPath?.let { imageUrl(it, "w500") },
        actors = null,
        genreIds = genreIds,
        voteAverage = voteAverage,
        voteCount = voteCount,
        originalLanguage = originalLanguage,
        popularity = popularity,
        releaseDate = firstAirDate,
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

/** Certification for a specific country; falls back to the preferred-country value. */
private fun TmdbReleaseDates?.forCountry(country: String): String? {
    val results = this?.results.orEmpty()
    results.firstOrNull { it.country == country }
        ?.releaseDates?.asSequence()
        ?.map { it.certification }
        ?.firstOrNull { validCert(it) }
        ?.let { return it }
    return certification()
}

private fun TmdbContentRatings?.forCountry(country: String): String? {
    val results = this?.results.orEmpty()
    results.firstOrNull { it.country == country }?.rating?.takeIf { validCert(it) }?.let { return it }
    return rating()
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

// Extra TMDB pages fetched when client-side filters narrow the candidate pool.
private const val SEARCH_PAGES_FILTERED = 2
// Titles checked for an age rating when filtering by certification.
private const val MAX_CERT_LOOKUPS = 40
// Concurrent certification lookups.
private const val CERT_CONCURRENCY = 6

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
