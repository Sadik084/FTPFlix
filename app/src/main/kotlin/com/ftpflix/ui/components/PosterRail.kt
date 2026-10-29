package com.ftpflix.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ftpflix.data.model.CatalogEntry
import com.ftpflix.ui.theme.Spacing

@Composable
fun PosterRail(
    title: String,
    items: List<CatalogEntry>,
    onItemClick: (CatalogEntry) -> Unit,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
    ageRatingOf: (CatalogEntry) -> String? = { (_, _, _, _, _, _, _, _, _) -> null },
) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            onSeeAll?.let {
                TextButton(onClick = it) { Text("See all") }
            }
        }
        Spacer(Modifier.height(Spacing.md))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            items(items, key = CatalogEntry::id, contentType = CatalogEntry::kind) {
                PosterCard(
                    title = it.title,
                    year = it.year,
                    posterUrl = it.posterUrl,
                    ageRating = ageRatingOf(it),
                    onClick = { onItemClick(it) },
                )
            }
        }
    }
}
