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
 * Pop (back) transitions use one shared duration with LinearEasing so the
 * predictive-back gesture tracks the finger 1:1; easing/duration mismatches
 * desync from it. Other slides use FastOutSlowIn.
 */
object Motion {
    /** Material 3 emphasized easing, reserved for the player modal (vertical, deliberate). */
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    const val PAGE_DURATION = 400
    const val PLAYER_DURATION = 380

    /** Horizontal push/tab slides (direction chosen at the call site). */
    val PageSlide = tween<IntOffset>(PAGE_DURATION, easing = FastOutSlowInEasing)

    /** Player modal slide-up (open only; back pops slide like any other page). */
    val PlayerSlide = tween<IntOffset>(PLAYER_DURATION, easing = EmphasizedEasing)
    val PlayerFade = tween<Float>(280, easing = LinearOutSlowInEasing)

    /** Pop (back) slides: one duration, LinearEasing, so the gesture tracks 1:1. */
    const val POP_DURATION = 350
    val PopSlide = tween<IntOffset>(POP_DURATION, easing = LinearEasing)

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
