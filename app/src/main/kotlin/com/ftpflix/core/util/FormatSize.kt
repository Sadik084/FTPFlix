package com.ftpflix.core.util

import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "Unknown size"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1024
        unit++
    } while (value >= 1024 && (unit < units.lastIndex))
    val digits = if (value >= 10) 0 else 1
    return String.format(Locale.US, "%.${digits}f %s", value, units[unit])
}

