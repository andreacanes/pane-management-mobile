package com.andreacanes.panemgmt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.andreacanes.panemgmt.data.AuthStore
import com.andreacanes.panemgmt.ui.detail.PaneDetailScreen
import com.andreacanes.panemgmt.ui.grid.PaneGridScreen
import com.andreacanes.panemgmt.ui.setup.SetupScreen

object Routes {
    const val SETUP = "setup"
    const val GRID = "grid"
    const val DETAIL_PATTERN = "detail/{paneId}"
    fun detail(paneId: String) = "detail/${java.net.URLEncoder.encode(paneId, "UTF-8")}"
}

@Composable
fun PaneMgmtApp() {
    val context = LocalContext.current
    val authStore = remember { AuthStore(context.applicationContext) }
    val auth by authStore.configFlow.collectAsState(initial = null)
    val navController = rememberNavController()
    val deepLinkPaneId by DeepLinkBus.paneIdFlow.collectAsState()

    val startDestination = if (auth == null) Routes.SETUP else Routes.GRID

    // Drop into the pane detail screen when a notification deep-link fires.
    // Requires auth to be present — otherwise the link is silently consumed
    // (tap drops you on SETUP, acceptable edge case).
    LaunchedEffect(deepLinkPaneId, auth) {
        val paneId = deepLinkPaneId ?: return@LaunchedEffect
        if (auth == null) {
            DeepLinkBus.consume()
            return@LaunchedEffect
        }
        navController.navigate(Routes.detail(paneId)) {
            launchSingleTop = true
        }
        DeepLinkBus.consume()
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.SETUP) {
            SetupScreen(
                authStore = authStore,
                onContinue = {
                    navController.navigate(Routes.GRID) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.GRID) {
            PaneGridScreen(
                authStore = authStore,
                onOpenPane = { paneId ->
                    navController.navigate(Routes.detail(paneId))
                },
                onLoggedOut = {
                    navController.navigate(Routes.SETUP) {
                        popUpTo(Routes.GRID) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = Routes.DETAIL_PATTERN,
            arguments = listOf(navArgument("paneId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val encodedId = backStackEntry.arguments?.getString("paneId") ?: return@composable
            val paneId = java.net.URLDecoder.decode(encodedId, "UTF-8")
            PaneDetailScreen(
                authStore = authStore,
                paneId = paneId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
