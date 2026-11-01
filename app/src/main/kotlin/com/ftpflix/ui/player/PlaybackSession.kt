package com.ftpflix.ui.player

import com.ftpflix.sources.resolve.EpisodeStream
import javax.inject.Inject
import javax.inject.Singleton

// In-memory series episodes so the player can switch episodes without huge nav args.
@Singleton
class PlaybackSession @Inject constructor() {

    var seriesTitle: String = ""
    var contentKey: String = ""
    var posterUrl: String? = null
    var episodes: List<EpisodeStream> = emptyList()

    /** season -> (episodeNumber -> TMDB episode name) */
    var episodeNames: Map<Int, Map<Int, String>> = emptyMap()

    /** season -> (episodeNumber -> ISO air date) */
    var episodeDates: Map<Int, Map<Int, String>> = emptyMap()

    var currentIndex: Int = -1

    val hasEpisodes: Boolean get() = episodes.isNotEmpty()

    fun current(): EpisodeStream? = episodes.getOrNull(currentIndex)

    fun next(): EpisodeStream? = episodes.getOrNull(currentIndex + 1)

    fun nameFor(season: Int?, episode: Int?): String? =
        episodeNames[season]?.get(episode)

    fun set(
        seriesTitle: String,
        contentKey: String,
        posterUrl: String?,
        episodes: List<EpisodeStream>,
        episodeNames: Map<Int, Map<Int, String>>,
        episodeDates: Map<Int, Map<Int, String>> = emptyMap(),
        currentIndex: Int
    ) {
        this.seriesTitle = seriesTitle
        this.contentKey = contentKey
        this.posterUrl = posterUrl
        this.episodes = episodes
        this.episodeNames = episodeNames
        this.episodeDates = episodeDates
        this.currentIndex = currentIndex
    }

    fun clear() {
        seriesTitle = ""
        contentKey = ""
        posterUrl = null
        episodes = emptyList()
        episodeNames = emptyMap()
        episodeDates = emptyMap()
        currentIndex = -1
    }
}
