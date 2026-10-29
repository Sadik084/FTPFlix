package com.ftpflix.core.util

import java.util.Base64

// URL-safe tokens for route arguments (ids can contain spaces and other unsafe chars).
fun String.toRouteToken(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))

fun String.fromRouteToken(): String =
    String(Base64.getUrlDecoder().decode(this), Charsets.UTF_8)
