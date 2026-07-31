package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
          Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            AppNavigation(modifier = Modifier.padding(innerPadding))
          }
        }
      }
    }
  }
}

@Composable
fun AppNavigation(modifier: Modifier = Modifier) {
  val navController = rememberNavController()
  NavHost(navController = navController, startDestination = "home", modifier = modifier) {
    composable("home") { HomeScreen(navController) }
    composable("guidance") { GuidanceScreen(navController) }
    composable("camera") { CameraScreen(navController) }
    composable(
      "camera_retake/{shotIndex}",
      arguments = listOf(navArgument("shotIndex") { type = NavType.IntType }),
    ) { backStackEntry ->
      // shotIndex is 1-based on the calling side (shot number the user is
      // redoing); CameraScreen/CaptureSession index shots 0-based internally.
      val shotIndex = (backStackEntry.arguments?.getInt("shotIndex") ?: 1) - 1
      CameraScreen(navController, retakeShotIndex = shotIndex)
    }
    composable("review") { ReviewScreen(navController) }
  }
}
