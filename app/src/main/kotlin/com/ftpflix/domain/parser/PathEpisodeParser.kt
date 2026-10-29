package com.ftpflix.domain.parser

import android.net.Uri

data class ParsedEpisode(
    val season: Int?,
    val episode: Int?,
)

// Extracts season/episode numbers from full URI paths or filenames.
object PathEpisodeParser {

    private val SXE = Regex("""(?i)\b[Ss](\d{1,2})[ ._-]?[Ee](\d{1,3})\b""")
    private val NXMM = Regex("""\b(\d{1,2})[xX](\d{2,3})\b""")
    private val FOUR_DIGIT = Regex("""(?i)[a-z_.-](\d{2})(\d{2})[._-]""")
    private val SEASON_WORD = Regex("""(?i)\b(?:[Ss]eason|[Ss])[ ._-]?(\d{1,2})\b""")
    private val EP_WORD = Regex("""(?i)\b(?:[Ee]pisode|[Ee]p|[Ee])[ ._-]?(\d{1,3})\b""")
    private val LEADING_EP = Regex("""^\s*(\d{1,3})\b""")

    fun parse(decodedUrlOrPath: String): ParsedEpisode {
        val path = Uri.decode(decodedUrlOrPath)
        val segments = path.split('/').filter { it.isNotBlank() }
        val fileName = segments.lastOrNull() ?: ""

        // Precedence: SxxEyy/1x02 anywhere -> 4-digit SSEE in filename (excluding years) -> Season word -> Episode word -> leading digits when season known.
        for (segment in segments) {
            SXE.find(segment)?.let { m ->
                val s = m.groupValues[1].toIntOrNull()
                val e = m.groupValues[2].toIntOrNull()
                if ((s != null && e != null && s in 1..99 && e in 1..999)) {
                    return ParsedEpisode(s, e)
                }
            }
            NXMM.find(segment)?.let { m ->
                val s = m.groupValues[1].toIntOrNull()
                val e = m.groupValues[2].toIntOrNull()
                if ((s != null && e != null && s in 1..99 && e in 1..999)) {
                    return ParsedEpisode(s, e)
                }
            }
        }

        FOUR_DIGIT.find(fileName)?.let { m ->
            val s = m.groupValues[1].toIntOrNull()
            val e = m.groupValues[2].toIntOrNull()
            if (s != null && e != null && s in 1..50 && e in 1..100) {
                // Exclude years: ".2019." is not S20E19.
                if (s * 100 + e !in 1900..2099) return ParsedEpisode(s, e)
            }
        }

        var season: Int? = null
        for (segment in segments) {
            SEASON_WORD.find(segment)?.let { m ->
                val parsedSeason = m.groupValues[1].toIntOrNull()
                if (parsedSeason != null && parsedSeason in 1..99) {
                    season = parsedSeason
                }
            }
        }

        var episode: Int? = null
        EP_WORD.find(fileName)?.let { m ->
            val parsedEp = m.groupValues[1].toIntOrNull()
            if (parsedEp != null && parsedEp in 1..999) {
                episode = parsedEp
            }
        }

        if (episode == null && season != null) {
            LEADING_EP.find(fileName)?.let { m ->
                val parsedEp = m.groupValues[1].toIntOrNull()
                if (parsedEp != null && parsedEp in 1..999) {
                    episode = parsedEp
                }
            }
        }

        return ParsedEpisode(season, episode)
    }
}
