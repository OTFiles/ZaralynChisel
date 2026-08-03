package com.zaralynchisel.ui

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import com.zaralynchisel.ui.components.LogViewerScreen
import com.zaralynchisel.ui.components.ZaralynChiselTheme
import com.zaralynchisel.ui.filepicker.FilePickerScreen
import com.zaralynchisel.ui.godmode.GodModeScreen
import com.zaralynchisel.ui.playermode.PlayerModeScreen
import com.zaralynchisel.ui.settings.SettingsScreen

class MainActivity : ComponentActivity() {

    companion object {
        /** Last time (System.currentTimeMillis) a mouse move/scroll was seen;
         *  0 = no mouse activity yet. Written by onGenericMotionEvent. */
        @Volatile
        var lastMouseMoveMs = 0L
    }
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
    const val LOG_VIEWER = "log_viewer"

    /** Encode worldPath so it's safe for use in a navigation route. */
    fun godMode(worldPath: String) = "god_mode/${Uri.encode(worldPath)}"
    fun playerMode(worldPath: String) = "player_mode/${Uri.encode(worldPath)}"
}

@Composable
fun ZaralynChiselNavHost() {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Storage permission launcher
    val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        android.Manifest.permission.MANAGE_EXTERNAL_STORAGE
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            navController.navigate(NavRoutes.FILE_PICKER)
        } else {
            // Still allow — SAF doesn't require storage permission
            navController.navigate(NavRoutes.FILE_PICKER)
        }
    }

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
                onOpenWorld = {
                    // Check storage permission before opening file picker
                    val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Environment.isExternalStorageManager()
                    } else {
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.READ_EXTERNAL_STORAGE
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if (hasPermission) {
                        navController.navigate(NavRoutes.FILE_PICKER)
                    } else {
                        permissionLauncher.launch(storagePermission)
                    }
                },
                onSettings = { navController.navigate(NavRoutes.SETTINGS) }
            )
        }

        composable(NavRoutes.FILE_PICKER) {
            FilePickerScreen(
                onOpenGodMode = { worldPath ->
                    navController.navigate(NavRoutes.godMode(worldPath))
                },
                onOpenPlayerMode = { worldPath ->
                    navController.navigate(NavRoutes.playerMode(worldPath))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = NavRoutes.GOD_MODE,
            arguments = listOf(navArgument("worldPath") { type = NavType.StringType })
        ) { backStackEntry ->
            val worldPath = Uri.decode(backStackEntry.arguments?.getString("worldPath") ?: return@composable)
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
            val worldPath = Uri.decode(backStackEntry.arguments?.getString("worldPath") ?: return@composable)
            PlayerModeScreen(
                worldPath = worldPath,
                onBack = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenLogViewer = { navController.navigate(NavRoutes.LOG_VIEWER) }
            )
        }

        composable(NavRoutes.LOG_VIEWER) {
            LogViewerScreen(onBack = { navController.popBackStack() })
        }
    }

    /** Global mouse detection (see docs: hover moves don't reach the activity,
     *  but wheel/button events do). The Player mode also listens for hover moves
     *  on its GL view; this catches the rest so the touch UI hides reliably. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.toolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            companion.lastMouseMoveMs = System.currentTimeMillis()
            return true
        }
        return super.onGenericMotionEvent(event)
    }
}