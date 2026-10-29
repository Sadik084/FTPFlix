package com.ftpflix.domain.parser

data class ParsedName(
    val title: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val resolution: Int?,
    val codec: String?,
    val isEpisode: Boolean,
    val isSample: Boolean = false
)

object TitleNormalizer {
    fun key(title: String, year: Int?): String =
        title.lowercase()
            .replace(Regex("""[^a-z0-9 ]"""), "")
            .replace(Regex("""\b(the|a|an)\b"""), "")
            .replace(Regex("""\s+"""), " ").trim() + (year?.let { "|$it" } ?: "")
}
