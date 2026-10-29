package com.ftpflix.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftpflix.data.local.FeedItemEntity
import com.ftpflix.data.model.CatalogEntry
import com.ftpflix.ui.components.EmptyState
import com.ftpflix.ui.components.ErrorState
import com.ftpflix.ui.components.GenreFilterRow
import com.ftpflix.ui.components.PosterCard
import com.ftpflix.ui.components.PosterSkeleton
import com.ftpflix.ui.components.SingleSelectSegmentedRow
import com.ftpflix.ui.theme.Spacing

@Composable
fun LibraryScreen(
    onOpen: (CatalogEntry) -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val selectedGenres by vm.selectedGenres.collectAsStateWithLifecycle()
    val genres by vm.genres.collectAsStateWithLifecycle()
    val ageRatings by vm.ageRatings.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val initialLoading by vm.initialLoading.collectAsStateWithLifecycle()
    val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    // Infinite scroll: fetch the next page while 8 cards from the end so new rows are
    // usually ready before they scroll in.
    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            (items.isNotEmpty()) && (lastVisible >= (items.size - 8))
        }
    }
    // Keyed on items.size too: after an append nearEnd can legitimately stay true
    // (fast scroll) and a static key would never re-fire.
    LaunchedEffect(nearEnd, items.size) {
        if (nearEnd) vm.loadMore()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SingleSelectSegmentedRow(
            options = LibraryTab.entries.map { it.name },
            selectedIndex = LibraryTab.entries.indexOf(tab).coerceAtLeast(0),
            onSelect = { vm.setTab(LibraryTab.entries[it]) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
        )

        if (genres.isNotEmpty()) {
            GenreFilterRow(
                genres = listOf("All" to selectedGenres.isEmpty()) +
                    genres.map { it to (it in selectedGenres) },
                onSelect = { name -> vm.toggleGenre(if (name == "All") "" else name) },
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.height(Spacing.md))
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            when {
                items.isEmpty() && initialLoading -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = Spacing.lg, end = Spacing.lg, bottom = Spacing.huge,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(12) { PosterSkeleton() }
                    }
                }
                items.isEmpty() && (error != null) -> {
                    ErrorState(
                        message = error.orEmpty(),
                        onRetry = vm::retry,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                items.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Rounded.VideoLibrary,
                        title = if (selectedGenres.isEmpty()) "Nothing here yet" else "No titles match your filters",
                        message = if (selectedGenres.isEmpty())
                            "Pull to refresh the library, or check back later."
                        else "Try different genres or clear the filter.",
                        actionLabel = if (selectedGenres.isEmpty()) null else "Clear filter",
                        onAction = if (selectedGenres.isEmpty()) null else { { vm.toggleGenre("") } },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                else -> {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = Spacing.lg, end = Spacing.lg, bottom = Spacing.huge,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items, key = FeedItemEntity::id, contentType = FeedItemEntity::kind) {
                            (_, _, _, kind, tmdbId, title, year, posterUrl, _, _, _) ->
                            PosterCard(
                                title = title,
                                year = year,
                                posterUrl = posterUrl,
                                ageRating = ageRatings["$kind:$tmdbId"],
                                onClick = {
                                    onOpen(
                                        CatalogEntry(
                                            id = "tmdb:${if (kind == "series") "tv" else "movie"}:$tmdbId",
                                            kind = kind,
                                            title = title,
                                            year = year,
                                            posterUrl = posterUrl,
                                            hasFile = false,
                                        )
                                    )
                                },
                            )
                        }
                        if (loadingMore) {
                            item(key = "loading-more", span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = Spacing.lg),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        }
                        if ((error != null) && !loadingMore) {
                            item(key = "error-more", span = { GridItemSpan(maxLineSpan) }) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = Spacing.sm),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        error.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(onClick = vm::retry) { Text("Retry") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
