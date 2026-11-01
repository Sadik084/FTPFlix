package com.ftpflix.sources.resolve

import androidx.compose.runtime.Immutable

@Immutable
data class EpisodeStream(
    val url: String,
    val host: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val sizeDisplay: String,
    val score: Int,
    val headers: Map<String, String> = emptyMap(),
)

@Immutable
data class SeriesSeason(
    val number: Int,
    val episodes: List<EpisodeStream>,
)

@Immutable
data class SeriesCatalog(
    val title: String,
    val seasons: Map<Int, SeriesSeason>,
    val bestGuess: EpisodeStream? = null
) {
    fun sortedSeasons(): List<SeriesSeason> =
        seasons.values.sortedBy(SeriesSeason::number)

    fun season(number: Int): List<EpisodeStream> =
        seasons[number]?.episodes ?: emptyList()
}
