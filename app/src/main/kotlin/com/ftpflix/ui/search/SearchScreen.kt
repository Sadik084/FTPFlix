@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ftpflix.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftpflix.data.model.CatalogEntry
import com.ftpflix.ui.components.EmptyState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import com.ftpflix.ui.components.PosterCard
import com.ftpflix.ui.components.PosterSkeleton
import com.ftpflix.ui.components.SearchFilterBottomSheet
import com.ftpflix.ui.components.SingleSelectSegmentedRow
import com.ftpflix.ui.theme.Spacing
import com.ftpflix.ui.theme.FtpTypography
import com.ftpflix.ui.search.SearchSort

@Composable
fun SearchScreen(
    onOpen: (CatalogEntry) -> Unit,
    vm: SearchViewModel = hiltViewModel(),
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val ageRatings by vm.ageRatings.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(top = Spacing.sm),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = vm::onQueryChange,
            placeholder = { Text("Search titles…") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    // Filter button with active badge
                    IconButton(onClick = { showFilters = true }) {
                        Box {
                            Icon(Icons.Rounded.FilterList, "Filters")
                            if (filters.hasActiveFilters) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(8.dp)
                                        .background(MaterialTheme.colorScheme.error, CircleShape)
                                )
                            }
                        }
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChange("") }) {
                            Icon(Icons.Rounded.Close, "Clear")
                        }
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
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { 
                keyboardController?.hide()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
        )

        Spacer(Modifier.height(Spacing.md))

        SingleSelectSegmentedRow(
            options = SearchFilter.entries.map { it.name },
            selectedIndex = SearchFilter.entries.indexOf(filters.type).coerceAtLeast(0),
            onSelect = { vm.onFilterChange(SearchFilter.entries[it]) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
        )

        // Active Filter Row - shows applied filters as removable chips
        if (filters.hasActiveFilters) {
            ActiveFilterRow(
                filters = filters,
                genres = vm.getGenres(),
                onRemoveGenre = { genreId -> vm.onGenreFilterChange(genreId) },
                onClearAll = { vm.clearFilters() },
                onChangeYear = { from, to -> vm.onYearFilterChange(from, to) },
                onChangeRating = { rating -> vm.onMinRatingChange(rating) },
                onChangeLanguage = { lang -> vm.onLanguageFilterChange(lang) },
                onChangeSort = { sort -> vm.onSortChange(sort) },
                onChangeCertification = { cert -> vm.onCertificationToggle(cert) },
            )
        }

        Spacer(Modifier.height(Spacing.md))

        val showResults = query.trim().length >= 2 || filters.hasActiveFilters
        if (!showResults) {
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
    
    // Conditionally composed; ModalBottomSheet must leave composition when hidden.
    if (showFilters) {
        SearchFilterBottomSheet(
            filters = filters,
            genres = vm.getGenres(),
            onApply = vm::onFiltersChange,
            onDismiss = { showFilters = false },
        )
    }
}

@Composable
private fun ActiveFilterRow(
    filters: SearchFilters,
    genres: List<com.ftpflix.ui.components.GenreItem>,
    onRemoveGenre: (Int) -> Unit,
    onClearAll: () -> Unit,
    onChangeYear: (Int?, Int?) -> Unit,
    onChangeRating: (Double) -> Unit,
    onChangeLanguage: (String) -> Unit,
    onChangeSort: (SearchSort) -> Unit,
    onChangeCertification: (String) -> Unit,
) {
    val activeChips = mutableListOf<@Composable () -> Unit>()
    
    filters.genres.forEach { genreId ->
        val genreName = genres.find { it.id == genreId }?.name ?: "Genre $genreId"
        activeChips.add({
            FilterChip(
                selected = false,
                onClick = { onRemoveGenre(genreId) },
                label = { Text(genreName, style = FtpTypography.labelSmall) },
                modifier = Modifier.padding(end = Spacing.xs),
            )
        })
    }
    
    if (filters.yearFrom != null || filters.yearTo != null) {
        val yearText = when {
            filters.yearFrom != null && filters.yearTo != null -> "${filters.yearFrom}–${filters.yearTo}"
            filters.yearFrom != null -> "${filters.yearFrom}+"
            else -> "–${filters.yearTo}"
        }
        activeChips.add({
            FilterChip(
                selected = false,
                onClick = { onChangeYear(null, null) },
                label = { Text(yearText, style = FtpTypography.labelSmall) },
                modifier = Modifier.padding(end = Spacing.xs),
            )
        })
    }
    
    if (filters.minRating > 0) {
        activeChips.add({
            FilterChip(
                selected = false,
                onClick = { onChangeRating(0.0) },
                label = { Text("★ ${filters.minRating}+", style = FtpTypography.labelSmall) },
                modifier = Modifier.padding(end = Spacing.xs),
            )
        })
    }
    
    if (filters.languages.isNotEmpty()) {
        filters.languages.forEach { lang ->
            activeChips.add({
                FilterChip(
                    selected = false,
                    onClick = { onChangeLanguage(lang) },
                    label = { Text(lang.uppercase(), style = FtpTypography.labelSmall) },
                    modifier = Modifier.padding(end = Spacing.xs),
                )
            })
        }
    }
    
    if (filters.sortBy != SearchSort.Relevance) {
        val sortName = when (filters.sortBy) {
            SearchSort.Popularity -> "Popularity"
            SearchSort.Rating -> "Rating"
            SearchSort.ReleaseDate -> "Release Date"
            SearchSort.Title -> "Title A-Z"
        }
        activeChips.add({
            FilterChip(
                selected = false,
                onClick = { onChangeSort(SearchSort.Relevance) },
                label = { Text(sortName, style = FtpTypography.labelSmall) },
                modifier = Modifier.padding(end = Spacing.xs),
            )
        })
    }
    
    filters.certification.forEach { cert ->
        activeChips.add({
            FilterChip(
                selected = false,
                onClick = { onChangeCertification(cert) },
                label = { Text(cert, style = FtpTypography.labelSmall) },
                modifier = Modifier.padding(end = Spacing.xs),
            )
        })
    }
    
    if (activeChips.isNotEmpty()) {
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            maxItemsInEachRow = Int.MAX_VALUE,
        ) {
            activeChips.forEach { chip -> chip() }
            TextButton(onClick = onClearAll) {
                Text("Clear All", style = FtpTypography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private val entryContentType: (CatalogEntry) -> Any? = { (_, _, _, _, _, _, _, _, _) -> "entry" }
