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
import com.ftpflix.ui.search.SearchCertifications
import com.ftpflix.ui.search.SearchFilter
import com.ftpflix.ui.search.SearchFilters
import com.ftpflix.ui.search.SearchSort
import com.ftpflix.ui.theme.FtpTypography
import com.ftpflix.ui.theme.Spacing
import java.util.Calendar

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

/** Oldest year accepted by the year-range filter. */
private const val MIN_YEAR = 1870

/** Latest year accepted by the year-range filter (a few years ahead for upcoming titles). */
private val MAX_YEAR: Int = Calendar.getInstance().get(Calendar.YEAR) + 3

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
    var certifications by remember(filters.certification) { mutableStateOf(filters.certification) }
    var certificationCountry by remember(filters.certificationCountry) { mutableStateOf(filters.certificationCountry) }
    var yearError by remember { mutableStateOf<String?>(null) }

    // Returns false when the draft is invalid; the sheet then stays open.
    fun commit(): Boolean {
        val from = yearFrom.toIntOrNull()
        val to = yearTo.toIntOrNull()
        if (from != null && (from < MIN_YEAR || from > MAX_YEAR)) {
            yearError = "Year must be between $MIN_YEAR and $MAX_YEAR"
            return false
        }
        if (to != null && (to < MIN_YEAR || to > MAX_YEAR)) {
            yearError = "Year must be between $MIN_YEAR and $MAX_YEAR"
            return false
        }
        if (from != null && to != null && from > to) {
            yearError = "“From” year must not be after “To” year"
            return false
        }
        yearError = null
        onApply(
            SearchFilters(
                type = type,
                genres = selectedGenreIds,
                languages = selectedLanguages,
                yearFrom = from,
                yearTo = to,
                minRating = minRating.toDoubleOrNull() ?: 0.0,
                sortBy = sortBy,
                certification = certifications,
                certificationCountry = certificationCountry,
            )
        )
        return true
    }

    fun resetAll() {
        type = SearchFilter.All
        selectedGenreIds = emptySet()
        selectedLanguages = emptySet()
        yearFrom = ""
        yearTo = ""
        minRating = ""
        sortBy = SearchSort.Relevance
        certifications = emptySet()
        certificationCountry = "US"
        yearError = null
    }

    fun toggleCertification(cert: String) {
        certifications = if (cert in certifications) certifications - cert else certifications + cert
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
                        if (commit()) onDismiss()
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
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            YearField(
                                label = "From",
                                value = yearFrom,
                                onValueChange = {
                                    yearFrom = it
                                    yearError = null
                                },
                                modifier = Modifier.weight(1f),
                            )
                            YearField(
                                label = "To",
                                value = yearTo,
                                onValueChange = {
                                    yearTo = it
                                    yearError = null
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        yearError?.let { message ->
                            Text(
                                text = message,
                                style = FtpTypography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
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

                FilterSection("Age Rating Country") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        SearchCertifications.COUNTRIES.forEach { country ->
                            FilterChip(
                                selected = certificationCountry == country.code,
                                onClick = {
                                    certificationCountry = country.code
                                    // Rating codes differ per country, so start over.
                                    certifications = emptySet()
                                },
                                label = { Text(country.label, style = FtpTypography.labelMedium) },
                            )
                        }
                    }
                }

                FilterSection("Age Rating") {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            FilterChip(
                                selected = certifications.isEmpty(),
                                onClick = { certifications = emptySet() },
                                label = { Text("Any", style = FtpTypography.labelMedium) },
                            )
                        }
                        Text(
                            text = "Movies",
                            style = FtpTypography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            SearchCertifications.movieCerts(certificationCountry).forEach { cert ->
                                val selected = cert in certifications
                                FilterChip(
                                    selected = selected,
                                    onClick = { toggleCertification(cert) },
                                    label = { Text(cert, style = FtpTypography.labelMedium) },
                                    leadingIcon = if (selected) {
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
                        Text(
                            text = "Series",
                            style = FtpTypography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            SearchCertifications.tvCerts(certificationCountry).forEach { cert ->
                                val selected = cert in certifications
                                FilterChip(
                                    selected = selected,
                                    onClick = { toggleCertification(cert) },
                                    label = { Text(cert, style = FtpTypography.labelMedium) },
                                    leadingIcon = if (selected) {
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
