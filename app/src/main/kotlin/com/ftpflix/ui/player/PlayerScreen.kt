package com.ftpflix.ui.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import com.ftpflix.core.config.FitMode
import com.ftpflix.core.util.formatBytes
import com.ftpflix.ui.components.ErrorState
import com.ftpflix.ui.components.RefreshRate
import com.ftpflix.ui.components.UpcomingTag
import com.ftpflix.ui.components.formatAirDate
import com.ftpflix.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@UnstableApi
private enum class Panel { Audio, Subs, Episodes, Info, Speed, Fit }

/** Snapshot of an in-progress vertical drag; [revision] restarts the hide timer. */
private data class GestureHud(
    val isBrightness: Boolean,
    /** 0f..1f of max volume or screen brightness. */
    val value: Float,
    val revision: Int,
)

/** One double-tap seek event; [id] makes every tap a fresh animation. */
private data class SeekPulse(val forward: Boolean, val id: Long)

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** Prototype glass tokens: translucent white fills with a hairline border. */
private val GlassFill = Color.White.copy(alpha = 0.18f)
private val GlassFillActive = Color.White.copy(alpha = 0.32f)
private val GlassBorder = Color.White.copy(alpha = 0.35f)
private val GlassBorderActive = Color.White.copy(alpha = 0.6f)

@UnstableApi
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    // Restores system state when the exit starts, not after it finishes.
    navTransition: Transition<EnterExitState>,
    vm: PlayerViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val showNextUp by vm.showNextUp.collectAsStateWithLifecycle()
    val currentEpisodeIndex by vm.currentEpisodeIndex.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(value = true) }
    var openPanel by remember { mutableStateOf<Panel?>(null) }
    var gestureHud by remember { mutableStateOf<GestureHud?>(null) }
    var hudRevision by remember { mutableIntStateOf(0) }
    var seekPulse by remember { mutableStateOf<SeekPulse?>(null) }
    var seekDragging by remember { mutableStateOf(value = false) }
    var brightnessValue by remember { mutableFloatStateOf(0.5f) }
    var volumeValue by remember { mutableFloatStateOf(0.5f) }
    val context = LocalContext.current
    val activity = context as? Activity
    val view = LocalView.current

    LaunchedEffect(Unit) {
        brightnessValue = activity?.window?.attributes?.screenBrightness
            ?.takeIf { it >= 0f } ?: 0.5f
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volumeValue = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
    }

    // Auto-rotate to landscape and run immersive while the player is visible.
    val window = activity?.window
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // Let Crop/Stretch reach the display-cutout area in landscape (V3).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window?.let { w ->
                w.attributes = w.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
        }
        window?.let { w ->
            WindowInsetsControllerCompat(w, view).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        onDispose {
            view.keepScreenOn = false
            // Safety net only; real restore happens when the exit transition starts.
            // Never force PORTRAIT here: it would lock adaptive devices into portrait.
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            window?.let { w ->
                WindowInsetsControllerCompat(w, w.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window?.let { w ->
                    w.attributes = w.attributes.apply {
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    }
                }
            }
        }
    }
    // Restore orientation/system bars as soon as the exit starts (predictive-back safe).
    val leaving = navTransition.targetState == EnterExitState.PostExit
    LaunchedEffect(leaving) {
        if (leaving) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            window?.let { w ->
                WindowInsetsControllerCompat(w, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pause() }

    // Back closes an open side panel first, before it leaves the player (N8).
    BackHandler(enabled = openPanel != null) { openPanel = null }
    // Double-tap seek zones are handled here; system back must not exit while locked.
    BackHandler(enabled = ui.locked) { }

    LaunchedEffect(ui.isPlaying, ui.locked, controlsVisible, seekDragging) {
        vm.setControlsVisible(controlsVisible)
        if (ui.isPlaying && controlsVisible && !seekDragging) {
            // Locked screens hide faster: the dim overlay shouldn't sit on the video for the full 4s.
            delay(if (ui.locked) 2.6.seconds else 4.seconds)
            controlsVisible = false
        }
    }

    // Paused player is a static screen; keep the screen on only while playing.
    LaunchedEffect(ui.isPlaying) {
        view.keepScreenOn = ui.isPlaying
        if (ui.isPlaying) RefreshRate.normal(view) else RefreshRate.low(view)
    }
    val locked by rememberUpdatedState(ui.locked)
    val dragAdjuster = remember {
        DragAdjuster { isBrightness, value ->
            if (isBrightness) brightnessValue = value else volumeValue = value
            hudRevision++
            gestureHud = GestureHud(isBrightness, value, hudRevision)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                // Tap toggles controls; press alone must not (it precedes drags and double-taps).
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        if (locked) return@detectTapGestures
                        val width = size.width
                        val forward = offset.x >= (width / 2f)
                        vm.seekBy(if (forward) 10_000 else -10_000)
                        seekPulse = SeekPulse(forward, System.nanoTime())
                    }
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        if (!locked) dragAdjuster.drag(context, dragAmount)
                    },
                    onDragStart = { offset ->
                        dragAdjuster.start(context, offset.x < (size.width / 2f))
                    },
                    onDragEnd = { dragAdjuster.reset() },
                    onDragCancel = { dragAdjuster.reset() }
                )
            }
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val maxW = maxWidth
            val maxH = maxHeight
            val vw = ui.videoWidth
            val vh = ui.videoHeight
            val par = ui.videoPixelAspect
            val surfaceModifier = Modifier.fitModifier(ui.fitMode, vw, vh, par, maxW, maxH, LocalDensity.current)
            // Centered wrapper clips the overflowing Crop surface; centering must not depend on modifier-order scope.
            // No graphicsLayer here: Stretch is just fillMaxSize, the renderer already stretches the
            // frame to whatever surface rect it is given; an extra scale double-scaled it and the
            // overflow was clipped (V2).
            Box(
                Modifier.fillMaxSize().clipToBounds(),
                contentAlignment = Alignment.Center
            ) {
                PlayerSurface(
                    player = vm.player,
                    modifier = surfaceModifier
                )
            }
        }

        // Media3 only decodes subtitle cues; something has to draw them.
        SubtitleOverlay(
            player = vm.player,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Sit above the control bar so cues stay readable.
                .padding(bottom = if (controlsVisible && !ui.locked) 170.dp else 48.dp)
                .fillMaxWidth(0.86f)
        )

        // Sliders appear ONLY while actively dragging (plus a short linger), not whenever controls are visible.
        val showSliders = !ui.locked && gestureHud != null
        AnimatedVisibility(
            visible = showSliders,
            enter = fadeIn(tween(150)) + slideInHorizontally { if (gestureHud?.isBrightness == true) -it / 4 else it / 4 },
            exit = fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            VerticalGestureSlider(
                value = brightnessValue,
                active = gestureHud?.isBrightness == true,
                modifier = Modifier.padding(start = 20.dp)
            )
        }
        AnimatedVisibility(
            visible = showSliders,
            enter = fadeIn(tween(150)) + slideInHorizontally { if (gestureHud?.isBrightness == false) it / 4 else -it / 4 },
            exit = fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            VerticalGestureSlider(
                value = volumeValue,
                active = gestureHud?.isBrightness == false,
                modifier = Modifier.padding(end = 20.dp)
            )
        }
        AnimatedVisibility(
            visible = showSliders && gestureHud?.isBrightness == true,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            PercentChip(
                percent = (brightnessValue * 100).roundToInt(),
                modifier = Modifier.padding(start = 44.dp)
            )
        }
        AnimatedVisibility(
            visible = showSliders && gestureHud?.isBrightness == false,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            PercentChip(
                percent = (volumeValue * 100).roundToInt(),
                modifier = Modifier.padding(end = 44.dp)
            )
        }
        LaunchedEffect(gestureHud) {
            if (gestureHud != null) {
                // Short linger after the last drag event, then fade.
                delay(900.milliseconds)
                gestureHud = null
            }
        }

        seekPulse?.let { pulse ->
            androidx.compose.runtime.key(pulse.id) {
                SeekPulseOverlay(
                    forward = pulse.forward,
                    onDone = { if (seekPulse?.id == pulse.id) seekPulse = null },
                    modifier = Modifier.align(
                        if (pulse.forward) Alignment.CenterEnd else Alignment.CenterStart
                    )
                )
            }
        }

        if (ui.buffering && ui.error == null) {
            // Thin, quiet ring; the default 4dp/40dp spinner read heavy and fought with the (now hidden) center controls.
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp),
                color = Color.White,
                strokeWidth = 3.dp
            )
        }

        ui.notice?.let { notice ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 84.dp)
                    .fillMaxWidth(0.86f),
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.72f),
            ) {
                Text(
                    notice,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (ui.error != null) {
            ErrorState(ui.error!!, onRetry = vm::retryPlayback, modifier = Modifier.align(Alignment.Center))
        }

        if (controlsVisible && !ui.locked && ui.error == null) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.70f),
                                    Color.Black.copy(alpha = 0.20f),
                                    Color.Transparent
                                )
                            )
                        )
                        .statusBarsPadding()
                        .padding(start = Spacing.lg, end = Spacing.lg, top = 6.dp, bottom = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassCircleButton(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        "Back",
                        onClick = onBack
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Column(Modifier.weight(1f)) {
                        val parts = ui.title.split(" \u00b7 ", limit = 2)
                        if (parts.size == 2) {
                            Text(
                                parts[0].uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.70f),
                                letterSpacing = 1.2.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            parts.last(),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    GlassCircleButton(Icons.Rounded.Lock, "Lock", size = 42.dp) {
                        vm.toggleLock()
                    }
                }

                // Hidden while buffering so the spinner and center controls don't stack.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .graphicsLayer { alpha = if (ui.buffering) 0f else 1f },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassCircleButton(
                        Icons.Rounded.Replay10, "Back 10s",
                        size = 56.dp, iconSize = 26.dp
                    ) { vm.seekBy(-10_000) }
                    Spacer(Modifier.width(Spacing.xl))
                    Box(contentAlignment = Alignment.Center) {
                        // Soft glow behind play; radial gradient, since blur clips to a rectangle.
                        Box(
                            Modifier
                                .size(140.dp)
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.38f),
                                            Color.White.copy(alpha = 0.16f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )
                        GlassCircleButton(
                            if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (ui.isPlaying) "Pause" else "Play",
                            size = 72.dp,
                            iconSize = 34.dp,
                            emphasized = true
                        ) { vm.togglePlayPause() }
                    }
                    Spacer(Modifier.width(Spacing.xl))
                    GlassCircleButton(
                        Icons.Rounded.Forward10, "Forward 10s",
                        size = 56.dp, iconSize = 26.dp
                    ) { vm.seekBy(10_000) }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.25f),
                                    Color.Black.copy(alpha = 0.75f)
                                )
                            )
                        )
                        .padding(start = Spacing.lg, end = Spacing.lg, bottom = 10.dp)
                ) {
                    SlimSeekbar(
                        positionMs = progress.positionMs,
                        durationMs = progress.durationMs,
                        onSeekStart = { seekDragging = true },
                        onSeekEnd = { seekDragging = false },
                        onSeek = { vm.seekTo(it) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            formatTime(progress.positionMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                        Text(
                            "-" + formatTime((progress.durationMs - progress.positionMs).coerceAtLeast(0)),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassPill(Icons.Rounded.GraphicEq, "Audio") { openPanel = Panel.Audio }
                        GlassPill(Icons.Rounded.Subtitles, "Subs") { openPanel = Panel.Subs }
                        if (vm.hasEpisodes) {
                            GlassPill(
                                Icons.AutoMirrored.Rounded.PlaylistPlay, "Episodes"
                            ) { openPanel = Panel.Episodes }
                        }
                        // Side panels instead of DropdownMenus: dropdowns inside the scrolling pill row clipped and mispositioned.
                        GlassPill(Icons.Rounded.Info, "Info") { openPanel = Panel.Info }
                        GlassPill(Icons.Rounded.Speed, "${ui.speed}x") { openPanel = Panel.Speed }
                        GlassPill(Icons.Rounded.AspectRatio, ui.fitMode.name) { openPanel = Panel.Fit }
                        GlassPill(Icons.Rounded.Lock, "Lock") { vm.toggleLock() }
                    }
                }
            }
        }

        // Locked: dim overlay + unlock pill; tap dim to dismiss, pill to unlock.
        if (ui.locked && controlsVisible && ui.error == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .pointerInput(Unit) {
                        // The overlay covers the gesture layer, so it handles the toggle itself.
                        detectTapGestures { controlsVisible = !controlsVisible }
                    }
            ) {
                Row(
                    Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(50))
                        .background(GlassFillActive)
                        .border(1.dp, GlassBorderActive, RoundedCornerShape(50))
                        .clickable {
                            vm.toggleLock()
                            controlsVisible = true
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Icon(Icons.Rounded.LockOpen, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Text(
                        "Tap to Unlock Screen",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }
            }
        }

        openPanel?.let { panel ->
            PlayerSidePanel(
                title = when (panel) {
                    Panel.Audio -> "Audio"
                    Panel.Subs -> "Subtitles"
                    Panel.Episodes -> "Episodes"
                    Panel.Info -> "Playback info"
                    Panel.Speed -> "Playback speed"
                    Panel.Fit -> "Fit to screen"
                },
                onDismiss = { openPanel = null }
            ) {
                when (panel) {
                    Panel.Audio -> TrackPanelContent(vm)
                    Panel.Subs -> SubsPanelContent(vm)
                    Panel.Info -> InfoPanelContent(ui = ui, stats = stats, progress = progress)
                    Panel.Speed -> OptionsPanelContent(
                        options = SPEED_OPTIONS.map { "${it}x" },
                        selectedIndex = SPEED_OPTIONS.indexOf(ui.speed),
                        onSelect = { index ->
                            vm.setSpeed(SPEED_OPTIONS[index])
                            openPanel = null
                        }
                    )
                    Panel.Fit -> OptionsPanelContent(
                        // "Original" is a true 1:1 pixel copy, capped to the screen (V3).
                        options = listOf("Fit", "Crop", "Stretch", "Original \u2013 1:1 pixels"),
                        selectedIndex = FitMode.entries.indexOf(ui.fitMode),
                        onSelect = { index ->
                            vm.setFitMode(FitMode.entries[index])
                        }
                    )
                    Panel.Episodes -> EpisodesPanelContent(
                        vm = vm,
                        currentIndex = currentEpisodeIndex,
                        onPlayEpisode = { index ->
                            vm.playEpisode(index)
                            openPanel = null
                        }
                    )
                }
            }
        }

        // Netflix-style next-up pill; visible regardless of controls.
        AnimatedVisibility(
            visible = showNextUp,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = Spacing.lg, bottom = 130.dp)
        ) {
            NextUpPill(
                label = vm.nextEpisodeLabel(),
                onPlay = { vm.playNextEpisode() },
                onDismiss = { vm.dismissNextUp() }
            )
        }
    }
}

@Composable
private fun GlassCircleButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp = 48.dp,
    iconSize: Dp = 22.dp,
    emphasized: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (emphasized) GlassFillActive else GlassFill)
            .border(1.dp, if (emphasized) GlassBorderActive else GlassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun GlassPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(GlassFill)
            .border(1.dp, GlassBorder, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1
        )
    }
}

/** Paper-thin prototype slider: 2.5dp hairline growing to 7dp while dragging, white fill rising from the bottom; no icon, percentage, or card. */
@Composable
private fun VerticalGestureSlider(
    value: Float,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    // Track thickens with a spring while dragging (paper-thin -> grabbable).
    val trackWidth by animateDpAsState(
        if (active) 7.dp else 2.5.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "sliderWidth"
    )
    val trackColor by animateColorAsState(
        if (active) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.20f),
        animationSpec = tween(150),
        label = "sliderTrack"
    )
    // Fill tracks the finger 1:1 during a drag; initial state starts at current level.
    val animatedFill = remember { Animatable(value) }
    LaunchedEffect(value) { animatedFill.animateTo(value, tween(150)) }
    val fill = if (animatedFill.isRunning) animatedFill.value else value
    Box(
        modifier
            .width(trackWidth)
            .height(120.dp)
            .clip(RoundedCornerShape(50))
            .background(trackColor)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(fill.coerceIn(0f, 1f))
                .align(Alignment.BottomCenter)
                .blur(3.dp)
                .background(Color.White.copy(alpha = 0.55f), RoundedCornerShape(50))
        )
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(fill.coerceIn(0f, 1f))
                .align(Alignment.BottomCenter)
                .background(Color.White.copy(alpha = 0.95f), RoundedCornerShape(50))
        )
    }
}

@Composable
private fun PercentChip(percent: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(GlassFill)
            .border(1.dp, GlassBorder, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            "$percent%",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White
        )
    }
}

/** Paper-thin seekbar: 2dp track, glowing fill, no thumb; expands to 6dp while scrubbing; tap anywhere to jump. */
@Composable
private fun SlimSeekbar(
    positionMs: Long,
    durationMs: Long,
    onSeekStart: () -> Unit,
    onSeekEnd: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction
        ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val thickness by animateDpAsState(
        if (dragFraction != null) 6.dp else 2.dp,
        animationSpec = tween(160),
        label = "seekThickness"
    )
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(26.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(thickness)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.25f))
            )
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(thickness + 2.dp)
                        .clip(RoundedCornerShape(50))
                        .blur(3.dp)
                        .background(Color.White.copy(alpha = 0.45f))
                )
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(thickness)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                )
            }
            // Full-height touch target for tap-to-seek and scrubbing.
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(durationMs) {
                        detectTapGestures { offset ->
                            if (durationMs > 0) {
                                onSeek((offset.x / size.width * durationMs).toLong())
                            }
                        }
                    }
                    .pointerInput(durationMs) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                onSeekStart()
                                dragFraction = if (durationMs > 0) {
                                    (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                } else 0f
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                if (durationMs > 0) {
                                    dragFraction = (change.position.x / size.width.toFloat())
                                        .coerceIn(0f, 1f)
                                }
                            },
                            onDragEnd = {
                                dragFraction?.let { f ->
                                    if (durationMs > 0) onSeek((f * durationMs).toLong())
                                }
                                dragFraction = null
                                onSeekEnd()
                            },
                            onDragCancel = {
                                dragFraction = null
                                onSeekEnd()
                            }
                        )
                    }
            )
        }
    }
}

/** Double-tap feedback: frosted glass circle with the skip glyph and "-10s"/"+10s" rippling (scale 0.8 -> 1.25) while fading out. */
@Composable
private fun SeekPulseOverlay(
    forward: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale = remember { Animatable(0.8f) }
    val alpha = remember { Animatable(0.95f) }
    LaunchedEffect(Unit) {
        launch { scale.animateTo(1.25f, tween(450)) }
        alpha.animateTo(0f, tween(450))
        onDone()
    }
    Box(
        modifier = modifier
            .padding(horizontal = Spacing.xl)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            }
            .size(84.dp)
            .background(Color.White.copy(alpha = 0.20f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.40f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (forward) Icons.Rounded.Forward10 else Icons.Rounded.Replay10,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
            Text(
                if (forward) "+10s" else "-10s",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
        }
    }
}

/** Draws decoded [Cue]s itself: Media3's [PlayerSurface] renders video only, so without this a selected subtitle track would never appear on screen. */
@UnstableApi
@Composable
private fun SubtitleOverlay(player: Player, modifier: Modifier = Modifier) {
    var cues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    if (cues.isEmpty()) return
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        cues.forEach { cue ->
            val text = cue.text?.toString().orEmpty()
            if (text.isBlank()) return@forEach
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/** Audio track picker (applies immediately; subtitles untouched). */
@UnstableApi
@Composable
private fun TrackPanelContent(vm: PlayerViewModel) {
    // Re-read tracks on every player event: caching left the panel stale.
    var tracks by remember { mutableStateOf(vm.player.currentTracks) }
    DisposableEffect(vm) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(newTracks: Tracks) {
                tracks = newTracks
            }
        }
        vm.player.addListener(listener)
        tracks = vm.player.currentTracks
        onDispose { vm.player.removeListener(listener) }
    }
    val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    if (groups.isEmpty()) {
        PanelHint()
        return
    }
    val current = selectedTrackIndex(tracks, C.TRACK_TYPE_AUDIO)
    val currentPair = current?.let { it.first to it.second }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        groups.forEach { g ->
            val gi = tracks.groups.indexOf(g)
            (0 until g.length).forEach { ti ->
                val format = g.getTrackFormat(ti)
                val label = trackLabel(format, "Audio ${ti + 1}")
                TrackRow(label = label, selected = currentPair == (gi to ti)) {
                    vm.selectTracks(gi, ti, null, null, touchSubtitles = false)
                }
            }
        }
    }
}

/** Subtitle picker with an explicit Off entry (applies immediately). */
@UnstableApi
@Composable
private fun SubsPanelContent(vm: PlayerViewModel) {
    var tracks by remember { mutableStateOf(vm.player.currentTracks) }
    DisposableEffect(vm) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(newTracks: Tracks) {
                tracks = newTracks
            }
        }
        vm.player.addListener(listener)
        tracks = vm.player.currentTracks
        onDispose { vm.player.removeListener(listener) }
    }
    val current = selectedTrackIndex(tracks, C.TRACK_TYPE_TEXT)
    val currentPair = current?.let { it.first to it.second }
    val subGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TrackRow(label = "Off", selected = current == null) {
            vm.selectTracks(null, null, null, null, touchSubtitles = true)
        }
        subGroups.forEach { g ->
            val gi = tracks.groups.indexOf(g)
            (0 until g.length).forEach { ti ->
                val format = g.getTrackFormat(ti)
                val label = trackLabel(format, "Subtitle ${ti + 1}")
                TrackRow(label = label, selected = currentPair == (gi to ti)) {
                    vm.selectTracks(null, null, gi, ti, touchSubtitles = true)
                }
            }
        }
    }
}

/** Season chips + episode list; tapping an episode switches playback in place. */
@UnstableApi
@Composable
private fun EpisodesPanelContent(
    vm: PlayerViewModel,
    currentIndex: Int,
    onPlayEpisode: (Int) -> Unit
) {
    val episodes = vm.playbackEpisodes
    val seasons = remember(episodes) {
        episodes.asSequence().mapNotNull { it.season }.distinct().sorted().toList()
    }
    var selectedSeason by remember(episodes) {
        mutableStateOf(episodes.getOrNull(currentIndex)?.season ?: seasons.firstOrNull())
    }
    Column(Modifier.fillMaxSize()) {
        if (seasons.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(seasons, key = { it }) { s ->
                    SeasonChip(
                        label = if (s == 0) "Specials" else "Season $s",
                        selected = s == selectedSeason,
                        onClick = { selectedSeason = s }
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(
                episodes.filter { it.season == selectedSeason },
                // TMDB-only rows share a blank url; key on season+episode too.
                key = { _, ep -> "${ep.season}-${ep.episode}-${ep.url}" }
            ) { _, ep ->
                val idx = episodes.indexOfFirst {
                    it.season == ep.season && it.episode == ep.episode && it.url == ep.url
                }
                val dateIso = vm.episodeDateFor(ep)
                val upcoming = vm.isEpisodeUpcoming(ep)
                TrackRow(
                    label = "E${ep.episode?.toString()?.padStart(2, '0') ?: "?"} \u00b7 ${vm.episodeNameFor(ep)}",
                    selected = idx == currentIndex,
                    sub = formatAirDate(dateIso)?.let { if (upcoming) "Airs $it" else it },
                    tag = if (upcoming) "Upcoming" else null,
                    dim = upcoming,
                ) { onPlayEpisode(idx) }
            }
        }
    }
}

@Composable
private fun PanelHint() {
    Text(
        "No audio tracks available yet. Wait for playback to start.",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.7f)
    )
}

@Composable
private fun SeasonChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) GlassFillActive else Color.White.copy(alpha = 0.08f))
            .border(
                1.dp,
                if (selected) GlassBorderActive else Color.White.copy(alpha = 0.12f),
                RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

/** Netflix-style "Up next" pill shown near the end of an episode. */
@Composable
private fun NextUpPill(label: String?, onPlay: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .clickable(onClick = onPlay)
            .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                "Up next",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f)
            )
            Text(
                label.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Rounded.PlayArrow, "Play next", tint = Color.White, modifier = Modifier.size(28.dp))
        IconButton(onClick = onDismiss) {
            Icon(Icons.Rounded.Close, "Dismiss", tint = Color.White.copy(alpha = 0.6f))
        }
    }
}

/** Index pair (groupIndex, trackIndex) of the currently selected track of [type]. */
private fun selectedTrackIndex(
    tracks: Tracks,
    type: Int
): Pair<Int, Int>? {
    tracks.groups.forEachIndexed { gi, group ->
        if (group.type == type) {
            for (ti in 0 until group.length) {
                if (group.isTrackSelected(ti)) return gi to ti
            }
        }
    }
    return null
}

@Composable
private fun TrackRow(
    label: String,
    selected: Boolean,
    sub: String? = null,
    tag: String? = null,
    dim: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) GlassFillActive else Color.White.copy(alpha = 0.05f))
            .border(
                1.dp,
                if (selected) GlassBorderActive else Color.White.copy(alpha = 0.10f),
                RoundedCornerShape(16.dp)
            )
            .alpha(if (dim) 0.55f else 1f)
            .clickable(enabled = !dim, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.85f),
                modifier = Modifier.weight(1f)
            )
            if (tag != null) {
                Spacer(Modifier.width(8.dp))
                UpcomingTag()
            }
        }
        if (sub != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                sub,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Generic option list (quality / speed / fit) in the side-panel style. */
@Composable
private fun OptionsPanelContent(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { index, label ->
            TrackRow(label = label, selected = index == selectedIndex) { onSelect(index) }
        }
    }
}

/** Release-group stamps muxers put in every track title ("mkvCinemas", "Telly", ...), useless for telling tracks apart, so dropped. */
private val TRACK_TITLE_NOISE = Regex(
    "(?i)(mkv|cinemas?|yify|yts|rarbg|ettv|telly|nodlabs|ingot|dhillon|" +
        "hon3y|esubs|msubs|subbed|dubbed|drd|jatk|tata|wbmm|hdhub)"
)

// Human track label; locale display names cover ISO 639-1 and 639-2.
private fun trackLabel(format: androidx.media3.common.Format, fallback: String): String {
    val language = languageLabel(format.language)
    val channels = channelLabel(format.channelCount)
    val title = format.label?.takeIf {
        it.isNotBlank() && !TRACK_TITLE_NOISE.containsMatchIn(it)
    }
    return when {
        language != null && channels != null -> "$language \u00b7 $channels"
        language != null && title != null && !title.equals(language, ignoreCase = true) ->
            "$language \u00b7 $title"
        language != null -> language
        title != null -> title
        else -> fallback
    }
}

private fun languageLabel(lang: String?): String? {
    val code = lang?.trim()?.lowercase().takeUnless { it.isNullOrEmpty() } ?: return null
    if (code == "und") return null // undetermined: fall through to title/fallback
    // Locale resolves both ISO 639-1 ("en") and 639-2 ("hin") codes.
    val display = runCatching { Locale.forLanguageTag(code).displayLanguage }.getOrNull()
    return display?.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
        ?: code.replaceFirstChar { it.uppercase() }
}

/** Audio channel layout, Netflix-style ("5.1", "Stereo"); null when unknown. */
private fun channelLabel(count: Int?): String? = when (count) {
    null, 0 -> null
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    7 -> "6.1"
    8 -> "7.1"
    else -> null
}

/** "Stats for nerds" side-panel: server, file size, codec/resolution, and a live bitrate graph (VLC/YouTube style). */
@Composable
private fun InfoPanelContent(
    ui: PlayerUi,
    stats: PlayerStats,
    progress: Progress
) {
    val currentKbps = stats.videoBitrateKbps
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        InfoRow(label = "Server", value = ui.serverHost ?: "Unknown")
        InfoRow(
            label = "File size",
            value = if (ui.fileSizeBytes > 0) formatBytes(ui.fileSizeBytes) else "Unknown"
        )
        InfoRow(label = "Resolution", value = resolutionLabel(stats.videoWidth, stats.videoHeight))
        InfoRow(label = "Video codec", value = stats.videoCodec ?: "Unknown")
        InfoRow(
            label = "Audio",
            value = listOfNotNull(stats.audioCodec, channelLabel(stats.audioChannels))
                .joinToString(" \u00b7 ").ifBlank { "Unknown" }
        )
        InfoRow(label = "Container", value = containerLabel(stats.containerMime))
        InfoRow(label = "Duration", value = formatTime(progress.durationMs))
        InfoRow(
            label = "Bitrate",
            value = if (currentKbps > 0) "$currentKbps kbps" else "\u2014"
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Bitrate graph",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.6f)
            )
            Text(
                stats.bitrateHistory.maxOrNull()?.let { "Peak $it kbps" }.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
        BitrateGraph(samples = stats.bitrateHistory)
    }
}

private fun resolutionLabel(width: Int, height: Int): String =
    if (width > 0 && height > 0) "${width}\u00d7${height}" else "Unknown"

private fun containerLabel(mime: String?): String {
    val raw = mime?.substringAfterLast('/')?.uppercase().orEmpty()
    return raw.ifBlank { "Unknown" }
}

/** Sparkline of recent bitrate samples (kbps): gradient fill + hairline stroke, min-max normalized. */
@Composable
private fun BitrateGraph(samples: List<Long>) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(4.dp)
    ) {
        val pts = samples.map { it.coerceAtLeast(0L) }
        if (pts.isEmpty()) return@Canvas
        val max = pts.max().coerceAtLeast(1L).toFloat()
        val min = pts.min().toFloat()
        val span = (max - min).coerceAtLeast(1f)
        val stepX = if (pts.size > 1) size.width / (pts.size - 1) else size.width

        val line = Path()
        pts.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - ((v.toFloat() - min) / span) * size.height
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        // Soft gradient fill under the curve; reads much better than a bare line on black.
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            fill,
            brush = Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.32f), Color.Transparent),
                endY = size.height
            )
        )
        drawPath(
            line,
            color = Color.White,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/** Labeled row for the info panel. */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp)
        )
    }
}

@Composable
private fun BoxScope.PlayerSidePanel(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    )
    AnimatedVisibility(
        visible = true,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        modifier = Modifier.align(Alignment.CenterEnd)
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(340.dp)
                .background(Color(0xD9141419))
                .statusBarsPadding()
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.9f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, "Close", tint = Color.White)
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            content()
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

// Surface sizing per fit mode; requires requiredSize so Crop isn't clamped by screen constraints (V1).
private fun Modifier.fitModifier(
    mode: FitMode,
    vw: Int,
    vh: Int,
    pixelAspect: Float,
    maxW: Dp,
    maxH: Dp,
    density: Density
): Modifier {
    if (vw <= 0 || vh <= 0) return fillMaxSize()
    val par = if (pixelAspect > 0f) pixelAspect else 1f
    // Display width (SAR applied) kept as Float; toInt() before dividing loses aspect precision.
    val dispWpx = vw * par
    val aspect = dispWpx / vh
    return when (mode) {
        // Largest video-aspect rect that fits inside the screen (letterbox via surface size).
        FitMode.Fit -> {
            val w = minOf(maxW, maxH * aspect)
            requiredSize(w, w / aspect)
        }
        // Smallest video-aspect rect covering the screen; requiredSize gives overflow to clip (V1).
        FitMode.Crop -> {
            val w = maxOf(maxW, maxH * aspect)
            requiredSize(w, w / aspect)
        }
        // Full-screen surface; the renderer stretches the frame, so no extra scale (V2).
        FitMode.Stretch -> fillMaxSize()
        // True 1:1 pixels, uniformly capped to the screen.
        FitMode.Original -> {
            val natW = with(density) { dispWpx.toDp() }
            val natH = with(density) { vh.toDp() }
            val s = minOf(1f, maxW / natW, maxH / natH)
            requiredSize(natW * s, natH * s)
        }
    }
}

// Vertical drag: left half brightness, right half volume, accumulated across the gesture.
private class DragAdjuster(
    private val onProgress: (isBrightness: Boolean, value: Float) -> Unit
) {
    private var brightness = false
    private var startBrightness = 0.5f
    private var startVolume = 0
    private var maxVolume = 1
    private var totalDrag = 0f

    fun start(context: Context, isLeftHalf: Boolean) {
        brightness = isLeftHalf
        totalDrag = 0f
        if (isLeftHalf) {
            val attrs = (context as? Activity)?.window?.attributes
            startBrightness = attrs?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
            onProgress(true, startBrightness)
        } else {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            onProgress(false, startVolume / maxVolume.toFloat())
        }
    }

    fun drag(context: Context, amount: Float) {
        totalDrag += amount
        val delta = -totalDrag / 500f
        if (brightness) {
            val activity = context as? Activity ?: return
            val attrs = activity.window.attributes
            val value = (startBrightness + delta).coerceIn(0.05f, 1f)
            attrs.screenBrightness = value
            activity.window.attributes = attrs
            onProgress(true, value)
        } else {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val target = (startVolume + delta * maxVolume).roundToInt().coerceIn(0, maxVolume)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            onProgress(false, target / maxVolume.toFloat())
        }
    }

    fun reset() {
        totalDrag = 0f
    }
}
