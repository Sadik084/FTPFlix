package com.ftpflix.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "cards")
data class CardEntity(
    @PrimaryKey val key: String,
    val imdbId: String?,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val matchedAt: Long,
    val failedAt: Long?
)

@Entity(tableName = "metadata_details", primaryKeys = ["provider", "id"])
data class MetadataEntity(
    val provider: String,
    val id: String,
    val kind: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val backgroundUrl: String?,
    val description: String?,
    val runtimeMinutes: Int?,
    val genres: String,
    val cast: String,
    val imdbRating: Double?,
    val fetchedAt: Long,
    val failedAt: Long?,
    /** Real IMDb id ("tt…"); added in v8. */
    val imdbId: String? = null,
    /** Series season count; added in v8. */
    val numberOfSeasons: Int? = null,
    /** Age certification (e.g. "PG-13", "TV-MA"); added in v9. */
    val ageRating: String? = null
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val mediaKey: String,
    val groupKey: String,
    /** Series title or movie title (never the raw filename). */
    val title: String,
    val posterUrl: String?,
    val streamUrl: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedAt: Long,
    /** "S01E03 \u00b7 Name" for series entries; null for movies. */
    val episodeLabel: String? = null
) {
    /** True when playback reached the end (Netflix-style watched state). */
    val isFinished: Boolean
        get() = durationMs > 0 && positionMs >= durationMs * 0.95
}

@Entity(tableName = "my_list")
data class MyListEntity(
    @PrimaryKey val key: String,
    val kind: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val addedAt: Long
)

@Entity(tableName = "resolved_streams")
data class ResolvedStreamEntity(
    @PrimaryKey val key: String,
    val title: String,
    val year: Int?,
    val url: String,
    val host: String,
    val sizeDisplay: String,
    val resolvedAt: Long,
    /** JSON stream headers; null when none. */
    val headers: String? = null
)

@Entity(
    tableName = "feed_snapshot",
    indices = [Index(value = ["shelfId", "rank"])]
)
data class FeedItemEntity(
    @PrimaryKey val id: String,
    val shelfId: String,
    val rank: Int,
    val kind: String,
    val tmdbId: Long,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val genres: String,
    val popularity: Double
)
