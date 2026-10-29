package com.ftpflix.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftpflix.data.model.CatalogEntry
import com.ftpflix.ui.components.EmptyState
import com.ftpflix.ui.components.PosterCard
import com.ftpflix.ui.components.PosterSkeleton
import com.ftpflix.ui.components.SingleSelectSegmentedRow
import com.ftpflix.ui.theme.Spacing

@Composable
fun SearchScreen(
    onOpen: (CatalogEntry) -> Unit,
    vm: SearchViewModel = hiltViewModel(),
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val ageRatings by vm.ageRatings.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = Spacing.sm),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = vm::onQueryChange,
            placeholder = { Text("Search titles…") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.onQueryChange("") }) {
                        Icon(Icons.Rounded.Close, "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(50),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
        )

        Spacer(Modifier.height(Spacing.md))

        SingleSelectSegmentedRow(
            options = SearchFilter.entries.map { it.name },
            selectedIndex = SearchFilter.entries.indexOf(filter).coerceAtLeast(0),
            onSelect = { vm.onFilterChange(SearchFilter.entries[it]) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
        )

        Spacer(Modifier.height(Spacing.md))

        if (query.trim().length < 2) {
            EmptyState(
                icon = Icons.Rounded.Search,
                title = "Search your library",
                message = "Find movies and shows by title.",
                modifier = Modifier.weight(1f),
            )
        } else if (searching && results.isEmpty()) {
            // First batch hasn't landed yet: shimmer grid instead of a spinner or a premature "no results".
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Spacing.lg, end = Spacing.lg, bottom = Spacing.huge,
                ),
                modifier = Modifier.weight(1f),
            ) {
                items(12) { PosterSkeleton() }
            }
        } else if (results.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Search,
                title = "No results for “$query”",
                message = "Check the spelling or try a different title.",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Spacing.lg, end = Spacing.lg, bottom = Spacing.huge,
                ),
                modifier = Modifier.weight(1f),
            ) {
                items(results, key = CatalogEntry::id, contentType = entryContentType) {
                    PosterCard(
                        title = it.title,
                        year = it.year,
                        posterUrl = it.posterUrl,
                        ageRating = ageRatings[it.id.removePrefix("tmdb:")],
                        onClick = { onOpen(it) },
                    )
                }
            }
        }
    }
}

private val entryContentType: (CatalogEntry) -> Any? = { (_, _, _, _, _, _, _, _, _) -> "entry" }
