package com.ftpflix.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ftpflix.core.config.AgeRatingStore
import com.ftpflix.core.config.RatingWarmup
import com.ftpflix.core.network.NetworkMonitor
import com.ftpflix.data.local.HistoryEntity
import com.ftpflix.data.model.CatalogEntry
import com.ftpflix.data.repository.FeedRepository
import com.ftpflix.data.repository.HistoryRepository
import com.ftpflix.data.repository.MyListRepository
import com.ftpflix.data.repository.TmdbFeedRepository
import com.ftpflix.domain.feed.Shelf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUi(
    val hero: CatalogEntry? = null,
    val heroInMyList: Boolean = false,
    val shelves: List<Shelf> = emptyList(),
    val continueWatching: List<HistoryEntity> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val feed: FeedRepository,
    private val history: HistoryRepository,
    private val feedRepo: TmdbFeedRepository,
    private val myList: MyListRepository,
    private val monitor: NetworkMonitor,
    ageRatings: AgeRatingStore,
    private val warmup: RatingWarmup,
) : ViewModel() {

    /** Poster age-rating tags keyed by media id ("movie:123"); empty when unknown. */
    val ageRatings: StateFlow<Map<String, String>> = ageRatings.ratings

    private val _state = MutableStateFlow(HomeUi())
    val state: StateFlow<HomeUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                feed.shelvesFlow(),
                history.continueWatching(),
                monitor.isOnline,
                myList.all()
            ) { shelves, continueRow, online, myListItems ->
                val myListKeys = myListItems.mapTo(mutableSetOf()) { it.key }
                val hero = pickHero(shelves)
                HomeUi(
                    hero = hero,
                    heroInMyList = hero != null && hero.id in myListKeys,
                    shelves = shelves,
                    continueWatching = continueRow,
                    isLoading = false,
                    offline = !online
                )
            }.collect { incoming ->
                _state.update { prev ->
                    // Keep the last hero so it doesn't blink out during a refresh.
                    val hero = incoming.hero ?: prev.hero
                    incoming.copy(
                        hero = hero,
                        heroInMyList = hero != null && (incoming.heroInMyList || hero.id == prev.hero?.id && prev.heroInMyList)
                    )
                }
                warmup.request(incoming.shelves.flatMap { it.items }.map { it.id })
            }
        }
        maybeBootstrap()
    }

    // Preferred movie shelves first, then any shelf with content.
    private fun pickHero(shelves: List<Shelf>): CatalogEntry? {
        val preferred = listOf("popular", "library", "trending")
        for (shelfId in preferred) {
            shelves.firstOrNull { it.id == shelfId }
                ?.items
                ?.firstOrNull()
                ?.let { return it }
        }
        return shelves.firstNotNullOfOrNull { it.items.firstOrNull() }
    }

    private fun maybeBootstrap() {
        viewModelScope.launch {
            try {
                if (feedRepo.needsRefresh()) {
                    feedRepo.refresh()
                }
            } catch (_: Exception) {
                // offline; cached/empty state is fine
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                feedRepo.refresh()
            } catch (_: Exception) {
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun toggleHeroMyList() {
        val hero = state.value.hero ?: return
        viewModelScope.launch {
            if (state.value.heroInMyList) {
                myList.remove(hero.id)
            } else {
                myList.add(
                    key = hero.id,
                    kind = hero.kind,
                    title = hero.title,
                    year = hero.year,
                    posterUrl = hero.posterUrl
                )
            }
        }
    }

    fun removeHistory(item: HistoryEntity) {
        viewModelScope.launch { history.remove(item.mediaKey) }
    }

    private val _refreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
}
