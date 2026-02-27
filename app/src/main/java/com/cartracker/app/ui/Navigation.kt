package com.cartracker.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cartracker.app.ui.screens.DashboardScreen
import com.cartracker.app.ui.screens.MapScreen
import com.cartracker.app.ui.screens.TripsScreen
import com.cartracker.app.ui.theme.*

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Map : Screen("map", "Map", Icons.Rounded.Map)
    data object Trips : Screen("trips", "Trips", Icons.Rounded.Route)
    data object Dashboard : Screen("dashboard", "Live", Icons.Rounded.Speed)
}

val bottomNavItems = listOf(Screen.Map, Screen.Trips, Screen.Dashboard)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarTrackerNavHost(viewModel: MainViewModel) {
    val navController = rememberNavController()

    Scaffold(
        containerColor = UberBlack,
        bottomBar = {
            NavigationBar(
                containerColor = UberCharcoal,
                tonalElevation = 0.dp
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                bottomNavItems.forEach { screen ->
                    val isSelected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = {
                            Icon(
                                screen.icon,
                                contentDescription = screen.label,
                                modifier = Modifier
                            )
                        },
                        label = {
                            Text(
                                screen.label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        selected = isSelected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = UberWhite,
                            selectedTextColor = UberWhite,
                            unselectedIconColor = UberTextTertiary,
                            unselectedTextColor = UberTextTertiary,
                            indicatorColor = Color.Transparent
                        )
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
