package com.ftpflix.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
object SearchRoute

@Serializable
object LibraryRoute

@Serializable
object MyListRoute

@Serializable
object SettingsRoute

@Serializable
object HistoryRoute

@Serializable
data class DetailsRoute(val key: String = "")

@Serializable
data class PlayerRoute(
    val mediaKey: String = "",
    val contentKey: String = "",
    val title: String = "",
    val posterUrl: String = "",
    val episodeLabel: String = "",
    // Release year; keeps stream re-resolution inside the right movie.
    val year: Int = 0
)

@Serializable
data class SeeAllRoute(val shelfId: String = "")
