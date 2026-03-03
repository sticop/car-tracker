package com.cartracker.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import com.cartracker.app.ui.screens.SettingsScreen
import com.cartracker.app.ui.screens.TripsScreen
import com.cartracker.app.ui.theme.UberBlack
import com.cartracker.app.ui.theme.UberCharcoal
import com.cartracker.app.ui.theme.UberTextTertiary
import com.cartracker.app.ui.theme.UberWhite

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Map : Screen("map", "Map", Icons.Rounded.Map)
    data object Trips : Screen("trips", "Trips", Icons.Rounded.Route)
    data object Dashboard : Screen("dashboard", "Live", Icons.Rounded.Speed)
    data object Settings : Screen("settings", "Settings", Icons.Rounded.Settings)
}

private val navItems = listOf(Screen.Map, Screen.Trips, Screen.Dashboard, Screen.Settings)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarTrackerNavHost(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val cityPrompt by viewModel.cityDownloadPrompt.collectAsState()
    val downloadProgress by viewModel.cityDownloadProgress.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(statusMessage) {
        val message = statusMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.clearStatusMessage()
    }

    fun navigateTo(screen: Screen) {
        navController.navigate(screen.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val content: @Composable (Modifier) -> Unit = { modifier ->
        NavHost(
            navController = navController,
            startDestination = Screen.Map.route,
            modifier = modifier
        ) {
            composable(Screen.Map.route) {
                MapScreen(viewModel = viewModel)
            }
            composable(Screen.Trips.route) {
                TripsScreen(
                    viewModel = viewModel,
                    onTripSelected = { tripId ->
                        viewModel.selectTrip(tripId)
                        navigateTo(Screen.Map)
                    }
                )
            }
            composable(Screen.Dashboard.route) {
                DashboardScreen(viewModel = viewModel)
            }
            composable(Screen.Settings.route) {
                SettingsScreen(viewModel = viewModel)
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(UberBlack)
    ) {
        val useTabletRail = maxWidth >= 840.dp

        if (useTabletRail) {
            Box(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(96.dp),
                    color = UberCharcoal,
                    tonalElevation = 0.dp
                ) {
                    NavigationRail(
                        modifier = Modifier
                            .fillMaxHeight()
                            .statusBarsPadding()
                            .navigationBarsPadding(),
                        containerColor = Color.Transparent
                    ) {
                        navItems.forEach { screen ->
                            val isSelected = currentDestination
                                ?.hierarchy
                                ?.any { it.route == screen.route } == true
                            NavigationRailItem(
                                selected = isSelected,
                                onClick = { navigateTo(screen) },
                                icon = {
                                    Icon(
                                        imageVector = screen.icon,
                                        contentDescription = screen.label
                                    )
                                },
                                label = {
                                    Text(
                                        text = screen.label,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) UberWhite else UberTextTertiary
                                    )
                                },
                                alwaysShowLabel = true
                            )
                        }
                    }
                }

                content(
                    Modifier
                        .fillMaxSize()
                        .padding(start = 96.dp)
                )
            }
        } else {
            Scaffold(
                containerColor = UberBlack,
                bottomBar = {
                    NavigationBar(
                        containerColor = UberCharcoal,
                        tonalElevation = 0.dp
                    ) {
                        navItems.forEach { screen ->
                            val isSelected = currentDestination
                                ?.hierarchy
                                ?.any { it.route == screen.route } == true
                            NavigationBarItem(
                                icon = {
                                    Icon(
                                        imageVector = screen.icon,
                                        contentDescription = screen.label
                                    )
                                },
                                label = {
                                    Text(
                                        text = screen.label,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                selected = isSelected,
                                onClick = { navigateTo(screen) },
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
                content(Modifier.padding(paddingValues))
            }
        }

        cityPrompt?.let { prompt ->
            val isDownloadingPromptCity =
                downloadProgress?.cityName?.equals(prompt.region.name, ignoreCase = true) == true

            AlertDialog(
                onDismissRequest = {
                    if (!isDownloadingPromptCity) {
                        viewModel.deferCityDownloadPrompt()
                    }
                },
                title = {
                    Text("Download offline map?")
                },
                text = {
                    val city = prompt.region.name
                    if (isDownloadingPromptCity) {
                        Text("Downloading $city map...")
                    } else {
                        Text("You are in $city. Download the full city map for offline use?")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.downloadPromptedCityMap() },
                        enabled = !isDownloadingPromptCity
                    ) {
                        Text(if (isDownloadingPromptCity) "Downloading" else "Download")
                    }
                },
                dismissButton = {
                    if (!isDownloadingPromptCity) {
                        TextButton(onClick = { viewModel.deferCityDownloadPrompt() }) {
                            Text("Later")
                        }
                    }
                }
            )
        }
    }
}
