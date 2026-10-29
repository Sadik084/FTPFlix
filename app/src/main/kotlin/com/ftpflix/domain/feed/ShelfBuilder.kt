package com.ftpflix.domain.feed

import androidx.compose.runtime.Immutable
import com.ftpflix.data.model.CatalogEntry

// Human title for a shelf id, shared by Home rails and See-All.
fun shelfTitle(shelfId: String): String = when (shelfId) {
    "trending" -> "Trending movies"
    "trending_tv" -> "Trending series"
    "popular" -> "Popular movies"
    "popular_tv" -> "Popular series"
    "new" -> "New movies"
    "new_tv" -> "New series"
    else ->
        if (shelfId.startsWith("genre_"))
            shelfId.removePrefix("genre_").replaceFirstChar { it.uppercase() }
        else shelfId.replaceFirstChar { it.uppercase() }
}

@Immutable
data class Shelf(
    val id: String,
    val title: String,
    val items: List<CatalogEntry>,
    val seeAll: Boolean = true
)
