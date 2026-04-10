package com.andreacanes.panemgmt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.andreacanes.panemgmt.data.AuthStore
import com.andreacanes.panemgmt.ui.grid.PaneGridScreen
import com.andreacanes.panemgmt.ui.setup.SetupScreen

object Routes {
    const val SETUP = "setup"
    const val GRID = "grid"
    const val DETAIL = "detail/{paneId}"
    fun detail(paneId: String) = "detail/$paneId"
}

@Composable
fun PaneMgmtApp() {
    val context = LocalContext.current
    val authStore = remember { AuthStore(context.applicationContext) }
    val auth by authStore.configFlow.collectAsState(initial = null)
    val navController = rememberNavController()

    val startDestination = if (auth == null) Routes.SETUP else Routes.GRID

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
                onLoggedOut = {
                    navController.navigate(Routes.SETUP) {
                        popUpTo(Routes.GRID) { inclusive = true }
                    }
                },
            )
        }
    }
}
