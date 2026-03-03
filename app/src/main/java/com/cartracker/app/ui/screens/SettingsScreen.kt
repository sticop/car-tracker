package com.cartracker.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cartracker.app.map.CachedCityMap
import com.cartracker.app.settings.MapStylePreference
import com.cartracker.app.ui.MainViewModel
import com.cartracker.app.ui.theme.UberBlack
import com.cartracker.app.ui.theme.UberCardDark
import com.cartracker.app.ui.theme.UberCharcoal
import com.cartracker.app.ui.theme.UberGreen
import com.cartracker.app.ui.theme.UberRed
import com.cartracker.app.ui.theme.UberTextSecondary
import com.cartracker.app.ui.theme.UberTextTertiary
import com.cartracker.app.ui.theme.UberWhite
import com.cartracker.app.util.FormatUtils
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val appSettings by viewModel.appSettings.collectAsState()
    val cachedCities by viewModel.cachedCityMaps.collectAsState()
    val mapCacheSizeMb by viewModel.mapCacheSizeMb.collectAsState()
    val downloadProgress by viewModel.cityDownloadProgress.collectAsState()

    var cityQuery by rememberSaveable { mutableStateOf("") }
    var pendingDeleteCity by remember { mutableStateOf<CachedCityMap?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshOfflineMapStats()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        UberBlack,
                        UberCharcoal.copy(alpha = 0.92f),
                        UberBlack
                    )
                )
            )
    ) {
        val isTablet = maxWidth >= 840.dp
        val horizontalPadding = if (isTablet) 32.dp else 20.dp
        val maxContentWidth = if (isTablet) 920.dp else 640.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = maxContentWidth)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Settings",
                    fontSize = if (isTablet) 32.sp else 28.sp,
                    fontWeight = FontWeight.Black,
                    color = UberWhite,
                    letterSpacing = (-0.5).sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                SectionCard(title = "Map & Offline") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = appSettings.defaultMapStyle == MapStylePreference.DETAIL,
                            onClick = { viewModel.setDefaultMapStyle(MapStylePreference.DETAIL) },
                            label = { Text("Detail") }
                        )
                        FilterChip(
                            selected = appSettings.defaultMapStyle == MapStylePreference.DARK,
                            onClick = { viewModel.setDefaultMapStyle(MapStylePreference.DARK) },
                            label = { Text("Dark") }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    SettingSwitchRow(
                        title = "Auto-center map",
                        description = "Keep map camera following current location",
                        checked = appSettings.autoCenterMap,
                        onCheckedChange = viewModel::setAutoCenterMap
                    )

                    SettingSwitchRow(
                        title = "City download prompt",
                        description = "Ask to download city map when entering a city",
                        checked = appSettings.offlineCityPromptEnabled,
                        onCheckedChange = viewModel::setOfflineCityPromptEnabled
                    )

                    SettingSwitchRow(
                        title = "Auto tile cache",
                        description = "Background-cache tiles while tracking",
                        checked = appSettings.autoTileCacheEnabled,
                        onCheckedChange = viewModel::setAutoTileCacheEnabled
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                SectionCard(title = "Trip Detection") {
                    SettingSliderRow(
                        title = "Parking speed threshold",
                        valueLabel = "${appSettings.parkingSpeedThresholdKmh.roundToInt()} km/h",
                        value = appSettings.parkingSpeedThresholdKmh,
                        range = 3f..30f,
                        steps = 26,
                        onValueChange = viewModel::setParkingSpeedThresholdKmh
                    )

                    val instantMin = appSettings.parkingSpeedThresholdKmh + 1f
                    SettingSliderRow(
                        title = "Instant trip threshold",
                        valueLabel = "${appSettings.instantTripSpeedThresholdKmh.roundToInt()} km/h",
                        value = appSettings.instantTripSpeedThresholdKmh,
                        range = instantMin..80f,
                        steps = (80f - instantMin).roundToInt().coerceAtLeast(1),
                        onValueChange = viewModel::setInstantTripSpeedThresholdKmh
                    )

                    SettingSliderRow(
                        title = "Moving confirmations",
                        valueLabel = "${appSettings.requiredMovingReadings} readings",
                        value = appSettings.requiredMovingReadings.toFloat(),
                        range = 1f..10f,
                        steps = 8,
                        onValueChange = { viewModel.setRequiredMovingReadings(it.roundToInt()) }
                    )

                    SettingSliderRow(
                        title = "Parking timeout",
                        valueLabel = "${appSettings.parkingTimeoutMinutes} min",
                        value = appSettings.parkingTimeoutMinutes.toFloat(),
                        range = 1f..30f,
                        steps = 28,
                        onValueChange = { viewModel.setParkingTimeoutMinutes(it.roundToInt()) }
                    )

                    SettingSliderRow(
                        title = "Trip accuracy requirement",
                        valueLabel = "${appSettings.minTripAccuracyMeters} m",
                        value = appSettings.minTripAccuracyMeters.toFloat(),
                        range = 5f..100f,
                        steps = 94,
                        onValueChange = { viewModel.setMinTripAccuracyMeters(it.roundToInt()) }
                    )

                    val minDetection = appSettings.minTripAccuracyMeters.toFloat()
                    SettingSliderRow(
                        title = "Detection accuracy requirement",
                        valueLabel = "${appSettings.minTripDetectionAccuracyMeters} m",
                        value = appSettings.minTripDetectionAccuracyMeters.toFloat(),
                        range = minDetection..150f,
                        steps = (150f - minDetection).roundToInt().coerceAtLeast(1),
                        onValueChange = { viewModel.setMinTripDetectionAccuracyMeters(it.roundToInt()) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                SectionCard(title = "GPS & Battery") {
                    SettingSwitchRow(
                        title = "Battery saver mode",
                        description = "Use slower polling when unplugged",
                        checked = appSettings.batterySaverModeEnabled,
                        onCheckedChange = viewModel::setBatterySaverModeEnabled
                    )

                    SettingSliderRow(
                        title = "Active GPS interval",
                        valueLabel = "${appSettings.activeGpsIntervalSeconds}s",
                        value = appSettings.activeGpsIntervalSeconds.toFloat(),
                        range = 1f..10f,
                        steps = 8,
                        onValueChange = { viewModel.setActiveGpsIntervalSeconds(it.roundToInt()) }
                    )

                    SettingSliderRow(
                        title = "Passive GPS interval",
                        valueLabel = "${appSettings.passiveGpsIntervalSeconds}s",
                        value = appSettings.passiveGpsIntervalSeconds.toFloat(),
                        range = 1f..30f,
                        steps = 28,
                        onValueChange = { viewModel.setPassiveGpsIntervalSeconds(it.roundToInt()) }
                    )

                    SettingSliderRow(
                        title = "Battery active interval",
                        valueLabel = "${appSettings.batteryActiveGpsIntervalSeconds}s",
                        value = appSettings.batteryActiveGpsIntervalSeconds.toFloat(),
                        range = 1f..30f,
                        steps = 28,
                        onValueChange = { viewModel.setBatteryActiveGpsIntervalSeconds(it.roundToInt()) }
                    )

                    SettingSliderRow(
                        title = "Battery parked interval",
                        valueLabel = "${appSettings.batteryParkedGpsIntervalSeconds}s",
                        value = appSettings.batteryParkedGpsIntervalSeconds.toFloat(),
                        range = 3f..180f,
                        steps = 176,
                        onValueChange = { viewModel.setBatteryParkedGpsIntervalSeconds(it.roundToInt()) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                SectionCard(title = "Data & Startup") {
                    SettingSwitchRow(
                        title = "Web sync",
                        description = "Send live and trip data to remote server",
                        checked = appSettings.webSyncEnabled,
                        onCheckedChange = viewModel::setWebSyncEnabled
                    )

                    SettingSwitchRow(
                        title = "Auto-start on boot",
                        description = "Restart tracking service after reboot",
                        checked = appSettings.autoStartOnBootEnabled,
                        onCheckedChange = viewModel::setAutoStartOnBootEnabled
                    )

                    SettingSwitchRow(
                        title = "Battery optimization prompt",
                        description = "Ask Android to ignore battery optimizations",
                        checked = appSettings.requestBatteryOptimizationExclusion,
                        onCheckedChange = viewModel::setRequestBatteryOptimizationExclusion
                    )

                    SettingSliderRow(
                        title = "Data retention",
                        valueLabel = "${appSettings.dataRetentionDays} days",
                        value = appSettings.dataRetentionDays.toFloat(),
                        range = 1f..365f,
                        steps = 363,
                        onValueChange = { viewModel.setDataRetentionDays(it.roundToInt()) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                SectionCard(title = "Offline City Maps") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Storage, contentDescription = null, tint = UberGreen)
                        Text(
                            text = "Total map cache: ${String.format(Locale.US, "%.1f", mapCacheSizeMb)} MB",
                            color = UberTextSecondary,
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (downloadProgress != null) {
                        Text(
                            text = "${downloadProgress?.stage}: ${downloadProgress?.cityName}",
                            color = UberWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = downloadProgress?.fraction ?: 0f,
                            modifier = Modifier.fillMaxWidth(),
                            color = UberGreen,
                            trackColor = UberCharcoal
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${downloadProgress?.completedTiles ?: 0} / ${downloadProgress?.totalTiles ?: 0} tiles",
                            color = UberTextTertiary,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    OutlinedTextField(
                        value = cityQuery,
                        onValueChange = { cityQuery = it },
                        label = { Text("City name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = downloadProgress == null
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.downloadCityMapForCurrentLocation() },
                            enabled = downloadProgress == null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.MyLocation, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Current city")
                        }

                        Button(
                            onClick = {
                                viewModel.downloadCityMapByName(cityQuery)
                                cityQuery = ""
                            },
                            enabled = downloadProgress == null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Download")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (cachedCities.isEmpty()) {
                        Text(
                            text = "No city map downloaded yet.",
                            color = UberTextSecondary,
                            fontSize = 13.sp
                        )
                    } else {
                        cachedCities.forEach { city ->
                            CachedCityRow(
                                city = city,
                                onRedownload = { viewModel.redownloadCachedCity(city.id) },
                                onDelete = { pendingDeleteCity = city },
                                enabled = downloadProgress == null
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        TextButton(
                            onClick = { showClearAllDialog = true },
                            enabled = downloadProgress == null
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = null,
                                tint = UberRed
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Clear all city maps",
                                color = UberRed,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    pendingDeleteCity?.let { city ->
        AlertDialog(
            onDismissRequest = { pendingDeleteCity = null },
            title = { Text("Remove offline map?") },
            text = { Text("Delete the downloaded map for ${city.cityName}.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeCachedCity(city.id)
                        pendingDeleteCity = null
                    }
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteCity = null }) { Text("Cancel") }
            }
        )
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear all offline maps?") },
            text = { Text("This removes downloaded map data for all cities.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllCachedCityMaps()
                        showClearAllDialog = false
                    }
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = UberCardDark
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                color = UberWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = UberWhite,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Text(
                text = description,
                color = UberTextTertiary,
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = UberWhite,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Text(
                text = valueLabel,
                color = UberTextSecondary,
                fontSize = 12.sp
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps.coerceAtLeast(0)
        )
    }
}

@Composable
private fun CachedCityRow(
    city: CachedCityMap,
    onRedownload: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = UberCharcoal
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Rounded.Map,
                    contentDescription = null,
                    tint = UberGreen
                )
                Column {
                    Text(
                        text = city.cityName,
                        color = UberWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "${city.tileCount} tiles • ${FormatUtils.formatDateTime(city.downloadedAtMillis)}",
                        color = UberTextTertiary,
                        fontSize = 11.sp
                    )
                }
            }

            Row {
                IconButton(onClick = onRedownload, enabled = enabled) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = "Redownload",
                        tint = if (enabled) UberWhite else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
                IconButton(onClick = onDelete, enabled = enabled) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "Delete",
                        tint = if (enabled) UberRed else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}
