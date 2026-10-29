package com.ftpflix.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.ftpflix.data.model.CatalogEntry
import com.ftpflix.domain.feed.shelfTitle
import com.ftpflix.ui.components.PosterCard
import com.ftpflix.ui.theme.Spacing

private val entryContentType: (CatalogEntry) -> Any? = { (_, _, _, _, _, _, _, _, _) -> "entry" }

@Composable
fun SeeAllScreen(
    shelfId: String,
    onBack: () -> Unit,
    onOpen: (CatalogEntry) -> Unit,
    vm: SeeAllViewModel = hiltViewModel(),
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
    val initialLoading by vm.initialLoading.collectAsStateWithLifecycle()
    val ageRatings by vm.ageRatings.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    // Infinite scroll: fetch the next page ~8 cards from the end.
    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            (items.isNotEmpty()) && (lastVisible >= (items.size - 8))
        }
    }
    // Also key on items.size so a fast scroll re-fires after an append.
    LaunchedEffect(nearEnd, items.size) {
        if (nearEnd) vm.loadMore()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
            }
            Text(shelfTitle(shelfId), style = MaterialTheme.typography.titleLarge)
        }
        when {
            (items.isEmpty()) && initialLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }
            items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing here yet. Pull to refresh the feed on Home.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                    contentPadding = PaddingValues(
                        start = Spacing.lg, end = Spacing.lg, bottom = Spacing.huge
                    ),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = CatalogEntry::id, contentType = entryContentType) {
                        PosterCard(
                            title = it.title,
                            year = it.year,
                            posterUrl = it.posterUrl,
                            ageRating = ageRatings[it.id.removePrefix("tmdb:")],
                            onClick = { onOpen(it) },
                        )
                    }
                    if (loadingMore) {
                        item(key = "loading-more", span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Spacing.lg),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
