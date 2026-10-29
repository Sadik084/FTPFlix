package com.ftpflix.ui.mylist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ftpflix.core.config.AgeRatingStore
import com.ftpflix.core.config.RatingWarmup
import com.ftpflix.data.local.MyListEntity
import com.ftpflix.data.repository.MyListRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MyListViewModel @Inject constructor(
    private val repo: MyListRepository,
    ageRatings: AgeRatingStore,
    private val warmup: RatingWarmup,
) : ViewModel() {

    /** Poster age-rating tags keyed by media id ("movie:123"); empty when unknown. */
    val ageRatings: StateFlow<Map<String, String>> = ageRatings.ratings

    val items: StateFlow<List<MyListEntity>> = repo.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            repo.all().collect { list -> warmup.request(list.map { it.key }) }
        }
    }

    fun remove(key: String) {
        viewModelScope.launch { repo.remove(key) }
    }
}
