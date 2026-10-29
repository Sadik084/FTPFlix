package com.ftpflix.ui.components

import android.os.Build
import android.view.View
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

private const val MIN_API = 35

object RefreshRate {
    fun low(view: View) {
        if (Build.VERSION.SDK_INT >= MIN_API) {
            view.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_LOW
        }
    }

    fun high(view: View) {
        if (Build.VERSION.SDK_INT >= MIN_API) {
            view.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
        }
    }

    fun normal(view: View) {
        if (Build.VERSION.SDK_INT >= MIN_API) {
            view.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_NORMAL
        }
    }
}

/** HIGH while the list scrolls, NORMAL ~300 ms after it stops. */
@Composable
fun ScrollRefreshRateHint(state: LazyListState) {
    val view = LocalView.current
    LaunchedEffect(state.isScrollInProgress) {
        if (Build.VERSION.SDK_INT < MIN_API) return@LaunchedEffect
        if (state.isScrollInProgress) {
            RefreshRate.high(view)
        } else {
            delay(300.milliseconds)
            RefreshRate.normal(view)
        }
    }
}

/** Static screen: hint a low refresh rate until leaving composition; the hint is
 * applied after [delayMs] so a page-enter transition still renders at full rate. */
@Composable
fun StaticRefreshRateHint(delayMs: Long = 0L) {
    val view = LocalView.current
    LaunchedEffect(Unit) {
        if (delayMs > 0L) delay(delayMs.milliseconds)
        RefreshRate.low(view)
    }
    DisposableEffect(Unit) {
        onDispose { RefreshRate.normal(view) }
    }
}
