package com.ftpflix.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset

/**
 * Motion specs for page transitions.
 *
 * Key rules to avoid glitches:
 *  - Fades always run to/from FULLY transparent: an exit ending at partial alpha parks
 *    the outgoing screen on its final frame until disposal ("pause then vanish"); pop
 *    slides end fully off-screen so they drop the fade.
 *  - Push/tab slides use FastOutSlowIn (starts with non-zero velocity); Emphasized
 *    starts at zero velocity, which reads as a frozen page for the first ~80ms.
 *  - POP transitions (predictive-back gestures) use LinearEasing and ONE shared duration,
 *    since Navigation seeks them by gesture progress and any easing or mismatched
 *    durations desync from the finger.
 *  - Nav taps are guarded by the current entry's lifecycle (canNavigate() in AppNavHost),
 *    not a time debounce.
 */
object Motion {
    /** Material 3 emphasized easing, reserved for the player modal (vertical, deliberate). */
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    const val PAGE_DURATION = 400
    const val PLAYER_DURATION = 380

    /** Horizontal push/tab slides (direction chosen at the call site). */
    val PageSlide = tween<IntOffset>(PAGE_DURATION, easing = FastOutSlowInEasing)

    /** Player modal slide-up (open only; the pop is a scale+fade, see PopScale/PopFade). */
    val PlayerSlide = tween<IntOffset>(PLAYER_DURATION, easing = EmphasizedEasing)
    val PlayerFade = tween<Float>(280, easing = LinearOutSlowInEasing)

    /** Pop specs: one duration, one easing (LinearEasing), so a predictive-back gesture tracks the finger 1:1 (no easing remap, no mismatched durations). */
    const val POP_DURATION = 350
    val PopSlide = tween<IntOffset>(POP_DURATION, easing = LinearEasing)
    val PopScale = tween<Float>(POP_DURATION, easing = LinearEasing)
    val PopFade = tween<Float>(POP_DURATION, easing = LinearEasing)

    val PressSpring = spring<Float>(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)
}

@Composable
fun Modifier.pressScale(pressed: Boolean): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = Motion.PressSpring,
        label = "press",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}
