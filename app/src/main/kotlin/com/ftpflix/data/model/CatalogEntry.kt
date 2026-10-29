package com.ftpflix.data.model

import androidx.compose.runtime.Immutable

/** Lightweight card data used by the Home feed and grids. */
@Immutable
data class CatalogEntry(
    val id: String,
    val kind: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val genres: List<String> = emptyList(),
    val popularity: Double? = null,
    val imdbId: String? = null,
    val hasFile: Boolean = true
) {
    val matchKey: String get() = "$id"
}
