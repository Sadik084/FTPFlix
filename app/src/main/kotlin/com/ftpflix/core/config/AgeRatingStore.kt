package com.ftpflix.core.config

import com.ftpflix.data.local.MetadataDao
import com.ftpflix.data.local.MetadataEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** In-memory age-rating lookup ("movie:123" -> "PG-13") fed from TMDB details; unknown titles stay untagged. */
@Singleton
class AgeRatingStore @Inject constructor(
    private val metadataDao: MetadataDao,
) {
    private val _ratings = MutableStateFlow<Map<String, String>>(emptyMap())
    val ratings: StateFlow<Map<String, String>> = _ratings.asStateFlow()

    /** Loads ratings persisted in the metadata cache; call once at app start. */
    suspend fun warmup() {
        if (_ratings.value.isNotEmpty()) return
        val cached = metadataDao.allOnce().asSequence()
            .mapNotNull(MetadataEntity::toRatingPair)
            .toMap()
        if (cached.isNotEmpty()) _ratings.value = cached
    }

    fun put(id: String, rating: String?) {
        val value = rating?.takeIf { it.isNotBlank() } ?: return
        _ratings.value += (id to value)
    }

    /** Known rating for [id], or null when not yet fetched. */
    fun get(id: String): String? = _ratings.value[id]
}

private fun MetadataEntity.toRatingPair(): Pair<String, String>? {
    val rating = ageRating?.takeIf { it.isNotBlank() } ?: return null
    return id to rating
}
