package com.ftpflix.ui.navigation

import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.ftpflix.core.util.fromRouteToken
import com.ftpflix.core.util.toRouteToken
import com.ftpflix.ui.details.DetailsScreen
import com.ftpflix.ui.home.HomeScreen
import com.ftpflix.ui.library.LibraryScreen
import com.ftpflix.ui.mylist.MyListScreen
import com.ftpflix.ui.player.PlayerScreen
import com.ftpflix.ui.search.HistoryScreen
import com.ftpflix.ui.search.SearchScreen
import com.ftpflix.ui.search.SeeAllScreen
import com.ftpflix.ui.settings.SettingsScreen
import com.ftpflix.ui.theme.Motion

// Only navigate when the current screen is idle, so taps during a transition are dropped.
private fun NavHostController.canNavigate() =
    currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED

private fun NavHostController.navigateDebounced(route: Any, block: NavOptionsBuilder.() -> Unit = {}) {
    if (!canNavigate()) return
    navigate(route) {
        launchSingleTop = true
        block()
    }
}

private fun NavHostController.navigateTopLevel(route: Any) {
    if (!canNavigate()) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

// Pop only when interactive and something is beneath; avoids double-pop on back (N8).
private fun NavHostController.popSafely() {
    val entry = currentBackStackEntry ?: return
    if ((entry.lifecycle.currentState == Lifecycle.State.RESUMED) && (previousBackStackEntry != null)) {
        popBackStack()
    }
}

private fun topLevelDestination(destination: androidx.navigation.NavDestination?): Boolean =
    (destination?.hasRoute<HomeRoute>() == true) ||
        (destination?.hasRoute<SearchRoute>() == true) ||
        (destination?.hasRoute<LibraryRoute>() == true) ||
        (destination?.hasRoute<MyListRoute>() == true) ||
        (destination?.hasRoute<SettingsRoute>() == true)

private fun topLevelIndex(destination: androidx.navigation.NavDestination?): Int =
    when {
        destination?.hasRoute<HomeRoute>() == true -> 0
        destination?.hasRoute<SearchRoute>() == true -> 1
        destination?.hasRoute<LibraryRoute>() == true -> 2
        destination?.hasRoute<MyListRoute>() == true -> 3
        destination?.hasRoute<SettingsRoute>() == true -> 4
        else -> -1
    }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val topLevel = topLevelDestination(destination)

    // Hide the nav suite on non-top-level destinations with smooth state management.
    val scaffoldState = rememberNavigationSuiteScaffoldState(
        initialValue = if (topLevel) NavigationSuiteScaffoldValue.Visible
        else NavigationSuiteScaffoldValue.Hidden,
    )

    LaunchedEffect(topLevel) {
        if (topLevel) scaffoldState.show() else scaffoldState.hide()
    }

    NavigationSuiteScaffold(
        state = scaffoldState,
        navigationSuiteItems = {
            item(
                selected = destination?.hasRoute<HomeRoute>() == true,
                onClick = { navController.navigateTopLevel(HomeRoute) },
                icon = { Icon(Icons.Rounded.Home, null) },
                label = { Text("Home") },
            )
            item(
                selected = destination?.hasRoute<SearchRoute>() == true,
                onClick = { navController.navigateTopLevel(SearchRoute) },
                icon = { Icon(Icons.Rounded.Search, null) },
                label = { Text("Search") },
            )
            item(
                selected = destination?.hasRoute<LibraryRoute>() == true,
                onClick = { navController.navigateTopLevel(LibraryRoute) },
                icon = { Icon(Icons.Rounded.VideoLibrary, null) },
                label = { Text("Library") },
            )
            item(
                selected = destination?.hasRoute<MyListRoute>() == true,
                onClick = { navController.navigateTopLevel(MyListRoute) },
                icon = { Icon(Icons.Rounded.Bookmarks, null) },
                label = { Text("My List") },
            )
            item(
                selected = destination?.hasRoute<SettingsRoute>() == true,
                onClick = { navController.navigateTopLevel(SettingsRoute) },
                icon = { Icon(Icons.Rounded.Settings, null) },
                label = { Text("Settings") },
            )
        },
    ) {
        // Transitions:
        //  - Tab switches slide by tab index.
        //  - Pushes slide the new page in; the old page parallaxes a quarter width.
        //  - Back pops slide out full width while the revealed page slides in a quarter,
        //    using one shared LinearEasing duration so the gesture tracks the finger.
        //  - Player opens as a vertical modal rise; back uses the same slide as any page.
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            enterTransition = {
                val initialIdx = topLevelIndex(initialState.destination)
                val targetIdx = topLevelIndex(targetState.destination)

                if ((initialIdx != -1) && (targetIdx != -1)) {
                    val fromLeft = targetIdx < initialIdx
                    slideInHorizontally(Motion.PageSlide) { if (fromLeft) -it else it }
                } else {
                    slideInHorizontally(Motion.PageSlide) { it }
                }
            },
            exitTransition = {
                when {
                    // Player is a vertical modal: whatever is underneath holds still (N2).
                    targetState.destination.hasRoute<PlayerRoute>() ->
                        ExitTransition.KeepUntilTransitionsFinished
                    else -> {
                        val initialIdx = topLevelIndex(initialState.destination)
                        val targetIdx = topLevelIndex(targetState.destination)
                        if ((initialIdx != -1) && (targetIdx != -1)) {
                            val exitLeft = targetIdx > initialIdx
                            slideOutHorizontally(Motion.PageSlide) { if (exitLeft) -it else it }
                        } else {
                            // Push: parallax a quarter width under the incoming page (N5).
                            slideOutHorizontally(Motion.PageSlide) { -it / 4 }
                        }
                    }
                }
            },
            popEnterTransition = {
                val initialIdx = topLevelIndex(initialState.destination)
                val targetIdx = topLevelIndex(targetState.destination)
                if ((initialIdx != -1) && (targetIdx != -1)) {
                    val fromLeft = targetIdx < initialIdx
                    slideInHorizontally(Motion.PopSlide) { if (fromLeft) -it / 4 else it / 4 }
                } else {
                    slideInHorizontally(Motion.PopSlide) { -it / 4 }
                }
            },
            popExitTransition = {
                val initialIdx = topLevelIndex(initialState.destination)
                val targetIdx = topLevelIndex(targetState.destination)
                if ((initialIdx != -1) && (targetIdx != -1)) {
                    val exitLeft = targetIdx > initialIdx
                    slideOutHorizontally(Motion.PopSlide) { if (exitLeft) -it else it }
                } else {
                    slideOutHorizontally(Motion.PopSlide) { it }
                }
            },
        ) {
            composable<HomeRoute> {
                HomeScreen(
                    onOpen = { (id, _, _, _, _, _, _, _, _) ->
                        navController.navigateDebounced(DetailsRoute(id.toRouteToken()))
                    },
                    onOpenKey = { (mediaKey, _, title, posterUrl, streamUrl, _, _, _, episodeLabel) ->
                        navController.navigateDebounced(
                            PlayerRoute(
                                mediaKey = streamUrl.toRouteToken(),
                                contentKey = mediaKey.toRouteToken(),
                                title = title,
                                posterUrl = posterUrl ?: "",
                                episodeLabel = episodeLabel.orEmpty(),
                            ),
                        )
                    },
                    onSearch = { navController.navigateTopLevel(SearchRoute) },
                    onSeeAll = { id -> navController.navigateDebounced(SeeAllRoute(id.toRouteToken())) },
                )
            }
            composable<SearchRoute> {
                SearchScreen(
                    onOpen = { (id, _, _, _, _, _, _, _, _) ->
                        navController.navigateDebounced(DetailsRoute(id.toRouteToken()))
                    },
                )
            }
            composable<LibraryRoute> {
                LibraryScreen(
                    onOpen = { (id, _, _, _, _, _, _, _, _) ->
                        navController.navigateDebounced(DetailsRoute(id.toRouteToken()))
                    },
                )
            }
            composable<MyListRoute> {
                MyListScreen(
                    onOpen = { (id, _, _, _, _, _, _, _, _) ->
                        navController.navigateDebounced(DetailsRoute(id.toRouteToken()))
                    },
                )
            }
            // Detail screens share the global transitions above.
            composable<DetailsRoute> {
                DetailsScreen(
                    onBack = { navController.popSafely() },
                    onPlay = { (mediaKey, contentKey, title, posterUrl, episodeLabel, year) ->
                        navController.navigateDebounced(
                            PlayerRoute(
                                mediaKey = mediaKey.toRouteToken(),
                                contentKey = contentKey.toRouteToken(),
                                title = title,
                                posterUrl = posterUrl ?: "",
                                episodeLabel = episodeLabel ?: "",
                                year = year ?: 0,
                            ),
                        )
                    },
                )
            }
            composable<PlayerRoute>(
                enterTransition = {
                    // Opens as a vertical modal rise; back slides out like any other page.
                    slideInVertically(Motion.PlayerSlide) { it } + fadeIn(Motion.PlayerFade)
                },
            ) {
                PlayerScreen(
                    onBack = { navController.popSafely() },
                    // The destination's own enter/exit transition; PlayerScreen restores
                    // orientation/system bars the moment its exit starts (N7).
                    navTransition = transition,
                )
            }
            composable<SeeAllRoute> { entry ->
                val (routeShelfId) = entry.toRoute<SeeAllRoute>()
                SeeAllScreen(
                    shelfId = routeShelfId.fromRouteToken(),
                    onBack = { navController.popSafely() },
                    onOpen = { (id, _, _, _, _, _, _, _, _) ->
                        navController.navigateDebounced(DetailsRoute(id.toRouteToken()))
                    },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    onOpenHistory = { navController.navigateDebounced(HistoryRoute) },
                )
            }
            composable<HistoryRoute> {
                HistoryScreen(
                    onBack = { navController.popSafely() },
                    onOpen = { key ->
                        navController.navigateDebounced(DetailsRoute(key.toRouteToken()))
                    },
                )
            }
        }
    }
}
