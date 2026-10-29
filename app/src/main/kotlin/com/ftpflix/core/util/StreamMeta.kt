package com.ftpflix.core.util

import android.net.Uri
import androidx.core.net.toUri

// Stream-URL helpers; resolution is carried in file names, so no network round-trip.
object StreamMeta {



    // Same normalized title from the last path segment; guards stream switching.
    fun sameMovieTitle(urlA: String?, urlB: String?): Boolean {
        if (urlA.isNullOrBlank() || urlB.isNullOrBlank()) return false
        val a = lastSegment(urlA)
        val b = lastSegment(urlB)
        if (a.isBlank() || b.isBlank()) return false
        val cleanA = com.ftpflix.util.TitleMatchingUtils.cleanTitle(a)
        val cleanB = com.ftpflix.util.TitleMatchingUtils.cleanTitle(b)
        return cleanA.isNotEmpty() && cleanA == cleanB
    }

    private fun lastSegment(url: String): String =
        runCatching { Uri.decode(url.toUri().lastPathSegment ?: "") }
            .getOrDefault(url.substringAfterLast('/'))
            .substringBeforeLast('.') // drop extension
}
