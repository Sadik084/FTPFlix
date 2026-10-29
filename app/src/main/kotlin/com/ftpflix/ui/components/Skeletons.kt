package com.ftpflix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ftpflix.ui.theme.Spacing
import com.valentinilk.shimmer.shimmer

@Composable
fun PosterSkeleton(width: Dp = 132.dp) {
    Column(Modifier.width(width).shimmer()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small),
        )
        Spacer(Modifier.height(Spacing.sm))
        Box(
            Modifier
                .fillMaxWidth(0.8f)
                .height(12.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.height(Spacing.xs))
        Box(
            Modifier
                .fillMaxWidth(0.4f)
                .height(10.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp)),
        )
    }
}

@Composable
fun SkeletonShelf() {
    Column {
        Box(
            Modifier
                .fillMaxWidth(0.3f)
                .height(20.dp)
                .padding(horizontal = Spacing.lg)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.height(Spacing.md))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            items(6) { PosterSkeleton() }
        }
    }
}
