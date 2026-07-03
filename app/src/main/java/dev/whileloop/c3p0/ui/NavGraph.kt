package dev.whileloop.c3p0.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.whileloop.c3p0.ui.screen.PairingScreen
import dev.whileloop.c3p0.ui.screen.ProfileScreen
import dev.whileloop.c3p0.ui.screen.SessionDashboard
import dev.whileloop.c3p0.ui.screen.StatsScreen

sealed class Screen(val route: String) {
    object Walk : Screen("walk")
    object Stats : Screen("stats")
    object Profile : Screen("profile")
    object Pairing : Screen("pairing")
}

@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Screen.Walk.route) {
        composable(Screen.Walk.route) {
            SessionDashboard()
        }
        composable(Screen.Stats.route) {
            StatsScreen()
        }
        composable(Screen.Profile.route) {
            ProfileScreen(onNavigateToPairing = { navController.navigate(Screen.Pairing.route) })
        }
        composable(Screen.Pairing.route) {
            PairingScreen(onDevicePaired = { navController.popBackStack() })
        }
    }
}
