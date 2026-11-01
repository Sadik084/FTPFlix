package com.ftpflix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// "2026-10-30" -> "Oct 30, 2026"; null when unparseable.
fun formatAirDate(iso: String?): String? {
    val d = iso?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    return d.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
}

// True when the date is after today.
fun isUpcoming(iso: String?): Boolean {
    iso ?: return false
    return runCatching { LocalDate.parse(iso).isAfter(LocalDate.now()) }.getOrDefault(false)
}

@Composable
fun UpcomingTag() {
    Text(
        "Upcoming",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
