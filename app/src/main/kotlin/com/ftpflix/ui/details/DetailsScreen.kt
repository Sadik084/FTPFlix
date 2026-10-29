package com.ftpflix.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.ftpflix.core.util.TmdbImage
import com.ftpflix.sources.resolve.EpisodeStream
import com.ftpflix.sources.resolve.SeriesSeason
import com.ftpflix.sources.resolve.StreamSource
import com.ftpflix.ui.components.PlayButton
import com.ftpflix.ui.components.ScrollRefreshRateHint
import com.ftpflix.ui.components.StaticRefreshRateHint
import com.ftpflix.ui.theme.Spacing

// Playback request: stream URL + display metadata for history rows.
data class PlayRequest(
    val mediaKey: String,
    val contentKey: String,
    val title: String,
    val posterUrl: String?,
    val episodeLabel: String? = null,
    /** Release year; keeps stream re-resolution inside the right movie. */
    val year: Int? = null,
)

@Composable
fun DetailsScreen(
    onBack: () -> Unit,
    onPlay: (PlayRequest) -> Unit,
    vm: DetailsViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val metadata = ui.metadata
    val context = LocalContext.current
    // Low refresh-rate kicks in after the enter transition finishes.
    StaticRefreshRateHint(delayMs = 700L)


    LaunchedEffect(metadata?.title) {
        if ((!ui.isSeries) && (ui.stream == null) && !ui.resolvingStream && (ui.streamError == null)) {
            vm.resolveStream()
        }
    }

    // Hand a resolved episode (pinned source) to navigation once ready.
    LaunchedEffect(ui.pendingPlay) {
        ui.pendingPlay?.let { request ->
            onPlay(request)
            vm.consumePendingPlay()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val listState = rememberLazyListState()
        ScrollRefreshRateHint(listState)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
            // Scaffold hides nav suite here and exposes real insets, so only small bottom padding is needed.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                bottom = Spacing.xl,
            ),
        ) {
            item(key = "hero") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            // Hero banner is 280dp tall; w780 is plenty and roughly half the bytes of w1280.
                            .data(
                                TmdbImage.atSize(
                                    metadata?.backgroundUrl ?: metadata?.posterUrl,
                                    TmdbImage.HERO,
                                ),
                            )
                            .crossfade(enable = false)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to MaterialTheme.colorScheme.background.copy(alpha = 0.4f),
                                    1f to MaterialTheme.colorScheme.background,
                                ),
                            ),
                    )
                }
            }

            item(key = "content") {
                Column(Modifier.padding(horizontal = Spacing.lg)) {
                    Text(
                        text = metadata?.title ?: "Title",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    val metaLine = listOfNotNull(
                        metadata?.year?.toString(),
                        metadata?.ageRating?.takeIf { it.isNotBlank() },
                        // Series metadata carries episode count here, not runtime.
                        metadata?.runtimeMinutes
                            ?.takeIf { (metadata.kind != "series") && (it > 0) }
                            ?.let { "$it min" },
                        metadata?.imdbRating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                    ).joinToString(" • ")
                    if (metaLine.isNotBlank()) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            metaLine,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val genres = metadata?.genres.orEmpty()
                    if (genres.isNotEmpty()) {
                        Spacer(Modifier.height(Spacing.sm))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            items(genres) { genre ->
                                TagChip(genre)
                            }
                        }
                    }

                    Spacer(Modifier.height(Spacing.lg))

                    SourceSelectorRow(
                        ui = ui,
                        onSelect = vm::selectSource,
                    )
                    if (ui.selectedSource == StreamSource.HD_HUB) {
                        Spacer(Modifier.height(Spacing.sm))
                        HdHubQualityRow(
                            ui = ui,
                            onSelect = vm::selectHdHubQuality,
                        )
                    }
                    Spacer(Modifier.height(Spacing.lg))

                    if (ui.isSeries) {
                        SeriesSection(
                            ui,
                            onSeasonSelect = vm::selectSeason,
                            onSelectEpisode = vm::prepareEpisodePlayback,
                            onPlayEpisode = vm::requestEpisodePlayback,
                            onRetry = vm::retrySeries,
                        )
                    } else {
                        MovieStreamSection(ui, metadata = metadata, onPlay = onPlay, onResolve = vm::resolveStream)
                    }

                    if (metadata != null) {
                        Spacer(Modifier.height(Spacing.sm))
                        OutlinedButton(onClick = { vm.toggleMyList() }) {
                            Icon(
                                if (ui.inMyList) Icons.Rounded.Check else Icons.Rounded.Add,
                                null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text(if (ui.inMyList) "In My List" else "Add to My List")
                        }
                    }

                    metadata?.description?.let { description ->
                        Spacer(Modifier.height(Spacing.xl))
                        Text("Synopsis", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(Spacing.sm))
                        var expanded by remember { mutableStateOf(value = false) }
                        Text(
                            description,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = if (expanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { expanded = !expanded },
                        )
                    }

                    metadata?.cast?.takeIf { it.isNotEmpty() }?.let { cast ->
                        Spacer(Modifier.height(Spacing.xl))
                        Text("Cast", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(Spacing.sm))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            items(cast) { name ->
                                TagChip(name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    if (metadata == null) {
                        Spacer(Modifier.height(Spacing.xl))
                        Text(
                            "Details unavailable. Try again later.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
        }

        if (ui.isLoading && (metadata == null)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun MovieStreamSection(
    ui: DetailsUi,
    metadata: com.ftpflix.data.model.MovieMetadata?,
    onPlay: (PlayRequest) -> Unit,
    onResolve: () -> Unit,
) {
    val stream = ui.stream
    when {
        stream != null -> {
            PlayButton(
                onClick = {
                    onPlay(
                        PlayRequest(
                            mediaKey = stream.url,
                            contentKey = ui.contentKey,
                            title = metadata?.title ?: stream.host,
                            posterUrl = metadata?.posterUrl,
                            year = metadata?.year,
                        ),
                    )
                },
            )
            val streamMetaLine = buildList {
                if (ui.selectedSource != StreamSource.AUTO) add(ui.selectedSource.label)
                if (stream.sizeDisplay.isNotBlank()) add(stream.sizeDisplay)
            }.joinToString(" • ")
            if (streamMetaLine.isNotBlank()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    streamMetaLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ui.resolvingStream -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(Spacing.sm))
                Text("Finding a stream…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        else -> {
            FilledTonalButton(onClick = onResolve) {
                Text("Find stream")
            }
            ui.streamError?.let {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SeriesSection(
    ui: DetailsUi,
    onSeasonSelect: (Int) -> Unit,
    onSelectEpisode: (EpisodeStream) -> Unit,
    onPlayEpisode: (EpisodeStream) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        ui.loadingSeries -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(Spacing.sm))
                Text("Finding seasons…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        ui.seriesError != null -> {
            Text(
                ui.seriesError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(Spacing.sm))
            FilledTonalButton(onClick = onRetry) {
                Text("Retry")
            }
        }
        ui.series != null -> {
            val catalog = ui.series
            val seasons = catalog.sortedSeasons()

            if (seasons.isNotEmpty()) {
                Text("Seasons", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Spacing.sm))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    items(seasons, key = SeriesSeason::number) { (seasonNumber, _) ->
                        val selected = seasonNumber == ui.selectedSeason
                        val label = if (seasonNumber == 0) "Specials" else "Season $seasonNumber"
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                                )
                                .clickable { onSeasonSelect(seasonNumber) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.md))
            }

            val episodes = ui.selectedSeason?.let { catalog.season(it) } ?: emptyList()
            if (episodes.isEmpty()) {
                Text(
                    "No episodes found for this season.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (ui.resolvingEpisode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            "Resolving from ${ui.selectedSource.label}…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }
                ui.episodeError?.let { err ->
                    Text(
                        err,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    episodes.forEach { episode ->
                        val code = episodeCode(episode)
                        // Real TMDB name when available, else "Episode N".
                        val name = ui.episodeNames[episode.season]?.get(episode.episode)
                            ?: "Episode ${episode.episode ?: ""}"
                        val episodeTitle = "$code  •  $name"
                        EpisodeRow(
                            episode = episode,
                            title = episodeTitle,
                        ) {
                            onSelectEpisode(episode)
                            onPlayEpisode(episode)
                        }
                    }
                }
            }
        }
    }
}

/** Source picker row (Auto / CircleFTP / RoarZone / HdHub), mirrors the season-chip tab style. */
@Composable
private fun SourceSelectorRow(ui: DetailsUi, onSelect: (StreamSource) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Source",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Spacing.xs))
        ui.availableSources.forEach { source ->
            SourceChip(
                label = source.label,
                selected = source == ui.selectedSource,
                status = ui.sourceResults[source],
            ) { onSelect(source) }
        }
    }
}

/** Chip with a small outcome indicator (loading spinner / filled dot for a found stream / muted dot for empty). */
@Composable
private fun SourceChip(
    label: String,
    selected: Boolean,
    status: SourceResult?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (status) {
            SourceResult.Loading -> CircularProgressIndicator(
                Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
            )
            is SourceResult.Ready -> Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.onPrimary else Color(0xFF43A047))
            )
            SourceResult.Empty -> Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline)
            )
            is SourceResult.Failed -> Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
            )
            null -> {}
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Quality tiers built from what the HdHub addon currently offers; "Auto" always present. */
@Composable
private fun HdHubQualityRow(ui: DetailsUi, onSelect: (Int?) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Quality",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Spacing.xs))
        if (ui.loadingHdhubQualities && ui.hdhubQualities.isEmpty()) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
            Spacer(Modifier.width(Spacing.xs))
        }
        SelectableChip(
            label = "Auto",
            selected = ui.hdhubHeight == null,
        ) { onSelect(null) }
        ui.hdhubQualities.forEach { height ->
            SelectableChip(
                label = qualityLabel(height),
                selected = ui.hdhubHeight == height,
            ) { onSelect(height) }
        }
        if (!ui.loadingHdhubQualities && ui.hdhubQualities.isEmpty()) {
            Text(
                "Best available",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun qualityLabel(height: Int): String = when {
    height >= 4320 -> "8K"
    height >= 2160 -> "4K"
    else -> "${height}p"
}

@Composable
private fun SelectableChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHighest
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

// Genres/cast pill; surfaceContainerHighest keeps contrast under artwork accents.
@Composable
private fun TagChip(
    label: String,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelMedium
) {
    Text(
        label,
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

private fun episodeCode(episode: EpisodeStream): String =
    "S" + (episode.season ?: 0).toString().padStart(2, '0') +
        "E" + (episode.episode ?: 0).toString().padStart(2, '0')

@Composable
private fun EpisodeRow(episode: EpisodeStream, title: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // File size only - the host label is an internal detail.
        if (episode.sizeDisplay.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                episode.sizeDisplay,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
