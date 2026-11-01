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

// Adds TMDB seasons the sources don't list yet; those episodes resolve on demand.
fun SeriesCatalog.withTmdbSeasons(counts: Map<Int, Int>): SeriesCatalog {
    if (counts.isEmpty()) return this
    val seasons = LinkedHashMap<Int, SeriesSeason>()
    sortedSeasons().forEach { seasons[it.number] = it }
    for ((number, count) in counts) {
        if (seasons.containsKey(number)) continue
        val episodes = (1..count).map { ep ->
            EpisodeStream(url = "", host = "", title = "", season = number, episode = ep, sizeDisplay = "", score = 0)
        }
        seasons[number] = SeriesSeason(number, episodes)
    }
    return SeriesCatalog(title, seasons, bestGuess)
}
