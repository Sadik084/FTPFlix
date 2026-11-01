package com.ftpflix.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TmdbPaged<T>(
    val page: Int = 1,
    val results: List<T> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 1,
    @SerialName("total_results") val totalResults: Int = 0
)

@Serializable
data class TmdbMovie(
    val id: Long = 0,
    val title: String = "",
    val name: String = "",
    @SerialName("original_title") val originalTitle: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("original_language") val originalLanguage: String = "",
    val popularity: Double = 0.0
)

@Serializable
data class TmdbTv(
    val id: Long = 0,
    val name: String = "",
    @SerialName("original_name") val originalName: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    val popularity: Double = 0.0
)

@Serializable
data class TmdbMovieDetails(
    val id: Long = 0,
    val title: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val runtime: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    val credits: TmdbCredits? = null,
    val videos: TmdbVideos? = null,
    /** Some detail responses include imdb_id at top level in addition to external_ids. */
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
    @SerialName("release_dates") val releaseDates: TmdbReleaseDates? = null
)

@Serializable
data class TmdbTvDetails(
    val id: Long = 0,
    val name: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("last_air_date") val lastAirDate: String? = null,
    @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    val credits: TmdbCredits? = null,
    val videos: TmdbVideos? = null,
    /** Some detail responses include imdb_id at top level in addition to external_ids. */
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
    @SerialName("content_ratings") val contentRatings: TmdbContentRatings? = null
)

@Serializable
data class TmdbGenre(val id: Int = 0, val name: String = "")

/** Movie certifications grouped per country (append_to_response=release_dates). */
@Serializable
data class TmdbReleaseDates(val results: List<TmdbReleaseDateCountry> = emptyList())

@Serializable
data class TmdbReleaseDateCountry(
    @SerialName("iso_3166_1") val country: String = "",
    @SerialName("release_dates") val releaseDates: List<TmdbReleaseDateEntry> = emptyList()
)

@Serializable
data class TmdbReleaseDateEntry(val certification: String = "")

/** TV parental ratings per country (append_to_response=content_ratings). */
@Serializable
data class TmdbContentRatings(val results: List<TmdbContentRating> = emptyList())

@Serializable
data class TmdbContentRating(
    @SerialName("iso_3166_1") val country: String = "",
    val rating: String = ""
)

/** External provider ids (append_to_response=external_ids); the real IMDb id feeds the RoarZone Emby search. */
@Serializable
data class TmdbExternalIds(
    @SerialName("imdb_id") val imdbId: String? = null
)

@Serializable
data class TmdbCredits(
    val cast: List<TmdbCast> = emptyList(),
    val crew: List<TmdbCrew> = emptyList()
)

@Serializable
data class TmdbCast(
    val id: Long = 0,
    val name: String = "",
    @SerialName("character") val character: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
    val order: Int = 0
)

@Serializable
data class TmdbCrew(val id: Long = 0, val name: String = "", val job: String = "")

@Serializable
data class TmdbVideos(val results: List<TmdbVideo> = emptyList())

@Serializable
data class TmdbVideo(
    val id: String = "",
    val key: String = "",
    val name: String = "",
    val site: String = "",
    val type: String = "",
    val size: Int = 0,
    val official: Boolean = false
)

@Serializable
data class TmdbGenreList(val genres: List<TmdbGenre> = emptyList())

@Serializable
data class TmdbSeasonDetails(
    val id: Long = 0,
    val name: String = "",
    val episodes: List<TmdbEpisode> = emptyList()
)

/** Season entry in /tv/{id}; summary only. */
@Serializable
data class TmdbSeasonStub(
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_count") val episodeCount: Int = 0,
)

@Serializable
data class TmdbTvSeasonList(
    val seasons: List<TmdbSeasonStub> = emptyList()
)

@Serializable
data class TmdbEpisode(
    val id: Long = 0,
    val name: String = "",
    val overview: String = "",
    @SerialName("episode_number") val episodeNumber: Int = 0,
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0
)

