package com.ftpflix.core.util

// TMDB image URLs embed a size segment; rewrite it per surface instead of storing variants.
object TmdbImage {

    /** Poster size that stays crisp for 120–160dp grid cards at 3x density. */
    const val POSTER_GRID = "w342"

    /** Full-bleed hero art; noticeably lighter than the stored w1280/w500. */
    const val HERO = "w780"

    // Replace the TMDB size segment; non-TMDB URLs pass through.
    fun atSize(url: String?, size: String): String? {
        if (url.isNullOrBlank() || !url.contains("image.tmdb.org/t/p/")) return url
        return url.replace(Regex("""(/t/p/)(?:w\d+|original)(?=/)""")) { match ->
            "${match.groupValues[1]}$size"
        }
    }
}
