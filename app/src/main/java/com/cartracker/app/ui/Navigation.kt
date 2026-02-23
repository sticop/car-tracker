package com.cartracker.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cartracker.app.ui.screens.DashboardScreen
import com.cartracker.app.ui.screens.MapScreen
import com.cartracker.app.ui.screens.TripsScreen

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Map : Screen("map", "Map", Icons.Filled.Map)
    data object Trips : Screen("trips", "Trips", Icons.Filled.History)
    data object Dashboard : Screen("dashboard", "Live", Icons.Filled.Speed)
}

val bottomNavItems = listOf(Screen.Map, Screen.Trips, Screen.Dashboard)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarTrackerNavHost(viewModel: MainViewModel) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                bottomNavItems.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
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
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Map.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(Screen.Map.route) {
                MapScreen(viewModel = viewModel)
            }
            composable(Screen.Trips.route) {
                TripsScreen(
                    viewModel = viewModel,
                    onTripSelected = { tripId ->
                        viewModel.selectTrip(tripId)
                        navController.navigate(Screen.Map.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Screen.Dashboard.route) {
                DashboardScreen(viewModel = viewModel)
            }
        }
    }
}
