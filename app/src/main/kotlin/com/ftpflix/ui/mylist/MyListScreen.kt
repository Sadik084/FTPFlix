package com.ftpflix.ui.mylist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftpflix.data.local.MyListEntity
import com.ftpflix.data.model.CatalogEntry
import com.ftpflix.ui.components.EmptyState
import com.ftpflix.ui.components.PosterCard
import com.ftpflix.ui.theme.Spacing

private val entryContentType: (MyListEntity) -> Any? = { (_, _, _, _, _, _) -> "entry" }

@Composable
fun MyListScreen(
    onOpen: (CatalogEntry) -> Unit,
    vm: MyListViewModel = hiltViewModel(),
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val ageRatings by vm.ageRatings.collectAsStateWithLifecycle()

    if (items.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.Bookmarks,
            title = "Your list is empty",
            message = "Tap “Add to My List” in a title’s details to save it here.",
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
        )
    } else {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Spacing.lg, end = Spacing.lg, top = Spacing.lg, bottom = Spacing.huge,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = MyListEntity::key, contentType = entryContentType) { (key, kind, title, year, posterUrl, _) ->
                PosterCard(
                    title = title,
                    year = year,
                    posterUrl = posterUrl,
                    ageRating = ageRatings[key.removePrefix("tmdb:")],
                    onClick = {
                        onOpen(
                            CatalogEntry(
                                id = key,
                                kind = kind,
                                title = title,
                                year = year,
                                posterUrl = posterUrl,
                                hasFile = true,
                            ),
                        )
                    },
                )
            }
        }
        }
    }
}
