package com.zaralynchisel.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zaralynchisel.ui.components.ZaralynChiselTheme
import com.zaralynchisel.ui.filepicker.FilePickerScreen
import com.zaralynchisel.ui.godmode.GodModeScreen
import com.zaralynchisel.ui.playermode.PlayerModeScreen
import com.zaralynchisel.ui.settings.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZaralynChiselTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ZaralynChiselNavHost()
                }
            }
        }
    }
}

object NavRoutes {
    const val HOME = "home"
    const val FILE_PICKER = "file_picker"
    const val GOD_MODE = "god_mode/{worldPath}"
    const val PLAYER_MODE = "player_mode/{worldPath}"
    const val SETTINGS = "settings"

    fun godMode(worldPath: String) = "god_mode/$worldPath"
    fun playerMode(worldPath: String) = "player_mode/$worldPath"
}

@Composable
fun ZaralynChiselNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = NavRoutes.HOME,
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) + fadeIn(tween(300))
        },
        exitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) + fadeOut(tween(300))
        },
        popEnterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) + fadeIn(tween(300))
        },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) + fadeOut(tween(300))
        }
    ) {
        composable(NavRoutes.HOME) {
            HomeScreen(
                onOpenWorld = { navController.navigate(NavRoutes.FILE_PICKER) },
                onSettings = { navController.navigate(NavRoutes.SETTINGS) }
            )
        }

        composable(NavRoutes.FILE_PICKER) {
            FilePickerScreen(
                onWorldSelected = { worldPath ->
                    navController.navigate(NavRoutes.godMode(worldPath))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = NavRoutes.GOD_MODE,
            arguments = listOf(navArgument("worldPath") { type = NavType.StringType })
        ) { backStackEntry ->
            val worldPath = backStackEntry.arguments?.getString("worldPath") ?: return@composable
            GodModeScreen(
                worldPath = worldPath,
                onSwitchToPlayer = {
                    navController.navigate(NavRoutes.playerMode(worldPath))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = NavRoutes.PLAYER_MODE,
            arguments = listOf(navArgument("worldPath") { type = NavType.StringType })
        ) { backStackEntry ->
            val worldPath = backStackEntry.arguments?.getString("worldPath") ?: return@composable
            PlayerModeScreen(
                worldPath = worldPath,
                onBack = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}