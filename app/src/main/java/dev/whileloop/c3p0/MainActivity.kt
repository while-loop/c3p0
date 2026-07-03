package dev.whileloop.c3p0

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.whileloop.c3p0.ui.NavGraph
import dev.whileloop.c3p0.ui.Screen
import dev.whileloop.c3p0.ui.theme.C3P0Theme
import dev.whileloop.c3p0.ui.viewmodel.MainViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            C3P0Theme {
                val mainViewModel: MainViewModel = hiltViewModel()
                DisposableEffect(mainViewModel) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            mainViewModel.refreshWeightFromHealthConnect()
                        }
                    }
                    lifecycle.addObserver(observer)
                    mainViewModel.refreshWeightFromHealthConnect()
                    onDispose { lifecycle.removeObserver(observer) }
                }

                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                val items = listOf(
                    Triple(Screen.Walk, Icons.Default.Home, "Walk"),
                    Triple(Screen.Stats, Icons.Default.List, "Stats"),
                    Triple(Screen.Profile, Icons.Default.Person, "Profile")
                )

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            items.forEach { (screen, icon, label) ->
                                NavigationBarItem(
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label) },
                                    selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                                    onClick = {
                                        navController.navigate(screen.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Surface(modifier = Modifier.padding(innerPadding)) {
                        NavGraph(navController = navController)
                    }
                }
            }
        }
    }
}
