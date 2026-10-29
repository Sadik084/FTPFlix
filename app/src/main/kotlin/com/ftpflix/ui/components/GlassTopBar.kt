package com.ftpflix.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ftpflix.ui.theme.Spacing

/** Top app bar: solid white title, circular search icon button, transparent overlay over hero art blending into the surface background as the user scrolls. */
@Composable
fun GlassTopBar(
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "FTPFLIX",
    /** 0f = top of feed (transparent over hero), 1f = content scrolled under the header. */
    scrolledFraction: Float = 0f,
) {
    val scrim by animateFloatAsState(
        targetValue = scrolledFraction.coerceIn(0f, 1f),
        animationSpec = tween(220),
        label = "topBarScrim",
    )

    val surfaceColor = MaterialTheme.colorScheme.surface

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(surfaceColor.copy(alpha = 0.95f * scrim)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 10.dp),
            ) {
                // Title logo forced to pure all-white (#FFFFFF) with high-contrast weight
                Text(
                    text = title.uppercase(),
                    fontWeight = FontWeight.Black,
                    fontSize = 21.sp,
                    letterSpacing = 1.2.sp,
                    color = Color.White,
                )

                // Search Icon Button
                Surface(
                    onClick = onSearch,
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = (0.45f + (0.35f * scrim)).coerceIn(0f, 0.85f)),
                    contentColor = Color.White,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Hairline divider indicator when content is scrolled underneath
            if (scrim > 0.05f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.20f * scrim)
                        )
                )
            }
        }
    }
}
