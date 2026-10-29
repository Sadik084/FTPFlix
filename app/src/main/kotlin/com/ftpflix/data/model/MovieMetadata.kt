package com.ftpflix.data.model

data class MovieMetadata(
    val id: String,
    val title: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    val backgroundUrl: String? = null,
    val description: String? = null,
    val runtimeMinutes: Int? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val imdbRating: Double? = null,
    val kind: String = "movie",
    /** Real IMDb id ("tt…") from TMDB external_ids; the RoarZone Emby search is keyed by it. */
    val imdbId: String? = null,
    /** Age certification (e.g. "PG-13", "TV-MA"); null when TMDB has none. */
    val ageRating: String? = null,
    /** Series only: season count from TMDB. */
    val numberOfSeasons: Int? = null
)
