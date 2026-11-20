@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.ftpflix.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.ftpflix.ui.search.SearchFilter
import com.ftpflix.ui.search.SearchFilters
import com.ftpflix.ui.search.SearchSort
import com.ftpflix.ui.theme.FtpTypography
import com.ftpflix.ui.theme.Spacing

/** ISO 639-1 -> English label pairs shown in the language picker. */
private val LANGUAGE_OPTIONS = listOf(
    "en" to "English",
    "es" to "Spanish",
    "fr" to "French",
    "de" to "German",
    "it" to "Italian",
    "pt" to "Portuguese",
    "ja" to "Japanese",
    "ko" to "Korean",
    "hi" to "Hindi",
    "ta" to "Tamil",
    "te" to "Telugu",
)

/** Preset minimum-rating options; empty string means no rating filter. */
private val RATING_OPTIONS = listOf("", "7.0", "8.0", "9.0")

private fun ratingLabel(value: String): String =
    if (value.isEmpty()) "Any rating" else "★ $value+"

private fun sortLabel(sort: SearchSort): String = when (sort) {
    SearchSort.Relevance -> "Relevance"
    SearchSort.Popularity -> "Popularity"
    SearchSort.Rating -> "Rating"
    SearchSort.ReleaseDate -> "Release Date"
    SearchSort.Title -> "Title A-Z"
}

/**
 * Filters bottom sheet. Drafts changes locally, commits on Apply, and must be
 * removed from composition when hidden.
 */
@Composable
fun SearchFilterBottomSheet(
    filters: SearchFilters,
    genres: List<GenreItem>,
    onApply: (SearchFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    // Sync local draft state with incoming filters when they change
    var type by remember(filters.type) { mutableStateOf(filters.type) }
    var selectedGenreIds by remember(filters.genres) { mutableStateOf(filters.genres) }
    var selectedLanguages by remember(filters.languages) { mutableStateOf(filters.languages) }
    var yearFrom by remember(filters.yearFrom) { mutableStateOf(filters.yearFrom?.toString() ?: "") }
    var yearTo by remember(filters.yearTo) { mutableStateOf(filters.yearTo?.toString() ?: "") }
    var minRating by remember(filters.minRating) { mutableStateOf(filters.minRating.takeIf { it > 0 }?.toString() ?: "") }
    var sortBy by remember(filters.sortBy) { mutableStateOf(filters.sortBy) }

    fun commit() {
        onApply(
            SearchFilters(
                type = type,
                genres = selectedGenreIds,
                languages = selectedLanguages,
                yearFrom = yearFrom.toIntOrNull(),
                yearTo = yearTo.toIntOrNull(),
                minRating = minRating.toDoubleOrNull() ?: 0.0,
                sortBy = sortBy,
            )
        )
    }

    fun resetAll() {
        type = SearchFilter.All
        selectedGenreIds = emptySet()
        selectedLanguages = emptySet()
        yearFrom = ""
        yearTo = ""
        minRating = ""
        sortBy = SearchSort.Relevance
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = Spacing.lg),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.xs, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Filters",
                    style = FtpTypography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = ::resetAll) {
                    Text("Reset")
                }
                Button(
                    onClick = {
                        commit()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text("Apply", style = FtpTypography.labelMedium)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close filters")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.xl),
            ) {
                FilterSection("Type") {
                    SingleSelectSegmentedRow(
                        options = SearchFilter.entries.map { it.name },
                        selectedIndex = SearchFilter.entries.indexOf(type).coerceAtLeast(0),
                        onSelect = { type = SearchFilter.entries[it] },
                    )
                }

                if (genres.isNotEmpty()) {
                    var showAllGenres by remember { mutableStateOf(false) }
                    val visibleGenres = remember(genres, selectedGenreIds, showAllGenres) {
                        if (showAllGenres) {
                            genres
                        } else {
                            val initial = genres.take(10).toSet()
                            val selectedExtra = genres.filter { it.id in selectedGenreIds }
                            (initial + selectedExtra).toList()
                        }
                    }

                    FilterSection("Genres") {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                visibleGenres.forEach { genre ->
                                    val isSelected = genre.id in selectedGenreIds
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            selectedGenreIds = if (isSelected) {
                                                selectedGenreIds - genre.id
                                            } else {
                                                selectedGenreIds + genre.id
                                            }
                                        },
                                        label = { Text(genre.name, style = FtpTypography.labelMedium) },
                                        leadingIcon = if (isSelected) {
                                            {
                                                Icon(
                                                    Icons.Rounded.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                            if (genres.size > 10) {
                                TextButton(
                                    onClick = { showAllGenres = !showAllGenres },
                                    modifier = Modifier.align(Alignment.Start),
                                ) {
                                    val remainingCount = genres.size - 10
                                    Text(
                                        if (showAllGenres) "Show Less" else "Show More (+$remainingCount)",
                                        style = FtpTypography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }

                FilterSection("Year Range") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        YearField(
                            label = "From",
                            value = yearFrom,
                            onValueChange = { yearFrom = it },
                            modifier = Modifier.weight(1f),
                        )
                        YearField(
                            label = "To",
                            value = yearTo,
                            onValueChange = { yearTo = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                FilterSection("Minimum Rating") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        RATING_OPTIONS.forEach { rating ->
                            FilterChip(
                                selected = minRating == rating,
                                onClick = { minRating = rating },
                                label = { Text(ratingLabel(rating), style = FtpTypography.labelMedium) },
                            )
                        }
                    }
                }

                FilterSection("Language") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        LANGUAGE_OPTIONS.forEach { (code, label) ->
                            FilterChip(
                                selected = code in selectedLanguages,
                                onClick = {
                                    selectedLanguages = if (code in selectedLanguages) {
                                        selectedLanguages - code
                                    } else {
                                        selectedLanguages + code
                                    }
                                },
                                label = { Text(label, style = FtpTypography.labelMedium) },
                            )
                        }
                    }
                }

                FilterSection("Sort By") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        SearchSort.entries.forEach { sort ->
                            FilterChip(
                                selected = sortBy == sort,
                                onClick = { sortBy = sort },
                                label = { Text(sortLabel(sort), style = FtpTypography.labelMedium) },
                                leadingIcon = if (sortBy == sort) {
                                    {
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = title,
            style = FtpTypography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun YearField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = label,
            style = FtpTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = value,
            onValueChange = { input ->
                val digits = input.filter { it.isDigit() }
                if (digits.length <= 4) onValueChange(digits)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(10.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
            placeholder = { Text("—", style = FtpTypography.bodyMedium) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
