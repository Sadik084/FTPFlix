package com.ftpflix.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftpflix.data.local.HistoryEntity
import com.ftpflix.data.model.CatalogEntry
import com.ftpflix.domain.feed.Shelf
import com.ftpflix.ui.components.ContinueWatchingRail
import com.ftpflix.ui.components.GlassTopBar
import com.ftpflix.ui.components.HeroBanner
import com.ftpflix.ui.components.OfflineBanner
import com.ftpflix.ui.components.PosterRail
import com.ftpflix.ui.components.ScrollRefreshRateHint
import com.ftpflix.ui.components.SkeletonShelf
import com.ftpflix.ui.theme.Spacing

private val shelfContentType: (Shelf) -> Any? = { (_, _, _, _) -> "shelf" }

@Composable
fun HomeScreen(
    onOpen: (CatalogEntry) -> Unit,
    onOpenKey: (HistoryEntity) -> Unit,
    onSearch: () -> Unit,
    onSeeAll: (String) -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val (hero, heroInMyList, shelves, continueWatching, isLoading, _, offline, _) =
        vm.state.collectAsStateWithLifecycle().value
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val ageRatings by vm.ageRatings.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    ScrollRefreshRateHint(listState)

    // Show skeletons during initial load or pull-to-refresh
    val showSkeletons = (isLoading && shelves.isEmpty()) || isRefreshing

    // Hero runs under the status bar; glass header sits over it with a scrim.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(Spacing.xl),
                contentPadding = PaddingValues(bottom = Spacing.huge),
            ) {
                item(key = "hero") {
                    if (showSkeletons) {
                        SkeletonShelf()
                    } else {
                        hero?.let { heroEntry ->
                            HeroBanner(
                                hero = heroEntry,
                                inMyList = heroInMyList,
                                onPlay = { onOpen(heroEntry) },
                                onToggleMyList = vm::toggleHeroMyList,
                            )
                        }
                    }
                }

                if (showSkeletons) {
                    items(3) { SkeletonShelf() }
                } else if (continueWatching.isNotEmpty()) {
                    item(key = "continue") {
                        ContinueWatchingRail(
                            items = continueWatching,
                            onItemClick = onOpenKey,
                            onItemRemove = vm::removeHistory,
                        )
                    }
                }

                if (!showSkeletons) {
                    items(shelves, key = Shelf::id, contentType = shelfContentType) { (shelfId, shelfTitle, shelfItems, shelfSeeAll) ->
                        PosterRail(
                            title = shelfTitle,
                            items = shelfItems,
                            onItemClick = onOpen,
                            onSeeAll = if (shelfSeeAll) ({ onSeeAll(shelfId) }) else null,
                        ) { (id, _, _, _, _, _, _, _, _) -> ageRatings[id.removePrefix("tmdb:")] }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = offline,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp),
        ) {
            OfflineBanner(onRefresh = vm::refresh)
        }

        // Scrim fades in as the hero scrolls under the header.
        val scrolledFraction by remember {
            derivedStateOf {
                val info = listState.layoutInfo
                val first = info.visibleItemsInfo.firstOrNull() ?: return@derivedStateOf 0f
                if (first.index != 0) return@derivedStateOf 1f
                val heroHeight = first.size.coerceAtLeast(1)
                (-first.offset.toFloat() / (heroHeight * 0.35f)).coerceIn(0f, 1f)
            }
        }
        GlassTopBar(
            title = "FTPFLIX",
            onSearch = onSearch,
            scrolledFraction = scrolledFraction,
        )
    }
}



