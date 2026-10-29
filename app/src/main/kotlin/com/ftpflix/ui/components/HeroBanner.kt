package com.ftpflix.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.ftpflix.core.util.TmdbImage
import com.ftpflix.data.model.CatalogEntry
import com.ftpflix.ui.theme.Spacing

// Hero: full-bleed poster with scrims and Play/My List actions.
@Composable
fun HeroBanner(
    hero: CatalogEntry,
    inMyList: Boolean = false,
    onPlay: () -> Unit,
    onToggleMyList: () -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(460.dp),
    ) {
        val context = LocalContext.current
        val imageRequest = remember(hero.posterUrl) {
            val sized = TmdbImage.atSize(hero.posterUrl, TmdbImage.HERO)
            if ((sized != null) && sized.startsWith("http")) {
                ImageRequest.Builder(context)
                    .data(sized)
                    .size(800, 1200)
                    .crossfade(220)
                    .build()
            } else null
        }
        if (imageRequest != null) {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            HeroPlaceholder(hero.title)
        }

        // Top scrim keeps the title readable over light artwork.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.75f),
                            Color.Black.copy(alpha = 0.40f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        // Bottom Multi-stop Scrim for Content Readability & Page Blend
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.20f),
                            Color.Black.copy(alpha = 0.85f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(Spacing.lg)
        ) {
            Text(
                hero.title,
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(hero.year?.toString(), hero.genres.firstOrNull()).joinToString(" • "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.md))

            // Action Buttons Row: Primary Play button + Secondary Add to My List button
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Primary Play Button
                Button(
                    onClick = onPlay,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        "Play",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                // Secondary Add to My List Button
                OutlinedButton(
                    onClick = onToggleMyList,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    ),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(
                        imageVector = if (inMyList) Icons.Rounded.Check else Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        if (inMyList) "In My List" else "Add to My List",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium)
                    )
                }
            }
        }
    }
}

@Composable
fun PlayButton(onClick: () -> Unit, label: String = "Play") {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .height(52.dp)
            .widthIn(min = 140.dp)
    ) {
        Icon(Icons.Rounded.PlayArrow, null)
        Spacer(Modifier.width(Spacing.sm))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun HeroPlaceholder(title: String) {
    val initial = title.trim().firstOrNull()?.uppercase() ?: "?"
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.primaryContainer
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            initial,
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
            )
        )
    }
}
