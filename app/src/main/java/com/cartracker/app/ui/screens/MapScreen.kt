package com.cartracker.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cartracker.app.data.LocationPoint
import com.cartracker.app.data.Trip
import com.cartracker.app.ui.MainViewModel
import com.cartracker.app.ui.TimeFilter
import com.cartracker.app.util.FormatUtils
import com.cartracker.app.util.SpeedColorUtils
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: MainViewModel) {
    val currentSpeed by viewModel.currentSpeed.collectAsState()
    val isMoving by viewModel.isMoving.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    val timeFilter by viewModel.timeFilter.collectAsState()
    val trips by viewModel.trips.collectAsState()
    val selectedTripId by viewModel.selectedTripId.collectAsState()
    val selectedTripWithPoints by viewModel.selectedTripWithPoints.collectAsState()
    val allPoints by viewModel.allPointsInRange.collectAsState()

    val cameraPositionState = rememberCameraPositionState()
    val scope = rememberCoroutineScope()

    // Auto-center on current location
    LaunchedEffect(currentLocation) {
        currentLocation?.let { loc ->
            if (selectedTripId == null) {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(loc.latitude, loc.longitude),
                        15f
                    ),
                    durationMs = 1000
                )
            }
        }
    }

    // Center on selected trip
    LaunchedEffect(selectedTripWithPoints) {
        selectedTripWithPoints?.let { tripData ->
            if (tripData.points.isNotEmpty()) {
                val bounds = LatLngBounds.builder()
                tripData.points.forEach { point ->
                    bounds.include(LatLng(point.latitude, point.longitude))
                }
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngBounds(bounds.build(), 100),
                    durationMs = 1000
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Google Map
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                mapType = MapType.NORMAL,
                isMyLocationEnabled = false
            ),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                myLocationButtonEnabled = false,
                mapToolbarEnabled = false
            )
        ) {
            // Draw routes
            if (selectedTripId != null) {
                // Show selected trip route with speed-colored segments
                selectedTripWithPoints?.let { tripData ->
                    DrawSpeedColoredRoute(tripData.points)
                    DrawTripMarkers(tripData.trip, tripData.points)
                }
            } else {
                // Show all trips in range with different colors
                val tripColors = listOf(
                    Color(0xFF2196F3), Color(0xFF4CAF50), Color(0xFFFF9800),
                    Color(0xFF9C27B0), Color(0xFFE91E63), Color(0xFF00BCD4)
                )
                // Group points by trip
                val pointsByTrip = allPoints.groupBy { it.tripId }
                pointsByTrip.entries.forEachIndexed { index, (_, points) ->
                    if (points.size >= 2) {
                        val color = tripColors[index % tripColors.size]
                        Polyline(
                            points = points.map { LatLng(it.latitude, it.longitude) },
                            color = color,
                            width = 8f
                        )
                    }
                }
            }

            // Current location marker
            currentLocation?.let { loc ->
                Marker(
                    state = MarkerState(position = LatLng(loc.latitude, loc.longitude)),
                    title = if (isMoving) "Moving: ${FormatUtils.formatSpeed(currentSpeed)}" else "Parked",
                    snippet = "Current Location"
                )
            }
        }

        // Top overlay - Speed indicator
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(16.dp)
        ) {
            // Speed display card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                ),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Status indicator
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (isMoving) Color(0xFF4CAF50) else Color(0xFFFF9800))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isMoving) "DRIVING" else "PARKED",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isMoving) Color(0xFF4CAF50) else Color(0xFFFF9800)
                        )
                    }

                    // Speed
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = String.format("%.0f", currentSpeed),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(SpeedColorUtils.getColorForSpeed(currentSpeed))
                        )
                        Text(
                            text = "km/h",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Time filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TimeFilter.entries.forEach { filter ->
                    FilterChip(
                        onClick = {
                            viewModel.setTimeFilter(filter)
                            viewModel.selectTrip(null)
                        },
                        label = { Text(filter.label) },
                        selected = timeFilter == filter,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            // Show selected trip info
            selectedTripWithPoints?.let { tripData ->
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Trip #${tripData.trip.id}",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "${FormatUtils.formatDateTime(tripData.trip.startTime)} → ${
                                    tripData.trip.endTime?.let { FormatUtils.formatTime(it) } ?: "Ongoing"
                                }",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = FormatUtils.formatDistance(tripData.trip.distanceMeters),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text("Distance", style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = FormatUtils.formatSpeed(tripData.trip.maxSpeedKmh),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(SpeedColorUtils.getColorForSpeed(tripData.trip.maxSpeedKmh))
                                )
                                Text("Max Speed", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        IconButton(onClick = { viewModel.selectTrip(null) }) {
                            Icon(Icons.Filled.Close, "Deselect trip")
                        }
                    }
                }
            }
        }

        // Bottom overlay - trip count
        if (selectedTripId == null && trips.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                )
            ) {
                Text(
                    text = "${trips.size} trip${if (trips.size != 1) "s" else ""} in ${timeFilter.label}",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        // Re-center button
        FloatingActionButton(
            onClick = {
                currentLocation?.let { loc ->
                    scope.launch {
                        cameraPositionState.animate(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(loc.latitude, loc.longitude),
                                15f
                            ),
                            durationMs = 500
                        )
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Filled.MyLocation, "Center on location")
        }
    }
}

@Composable
fun DrawSpeedColoredRoute(points: List<LocationPoint>) {
    if (points.size < 2) return

    // Draw polyline segments colored by speed
    for (i in 0 until points.size - 1) {
        val start = points[i]
        val end = points[i + 1]
        val avgSpeed = (start.speedKmh + end.speedKmh) / 2

        Polyline(
            points = listOf(
                LatLng(start.latitude, start.longitude),
                LatLng(end.latitude, end.longitude)
            ),
            color = Color(SpeedColorUtils.getColorForSpeed(avgSpeed)),
            width = 10f
        )
    }
}

@Composable
fun DrawTripMarkers(trip: Trip, points: List<LocationPoint>) {
    if (points.isEmpty()) return

    // Start marker
    val startPoint = points.first()
    Marker(
        state = MarkerState(position = LatLng(startPoint.latitude, startPoint.longitude)),
        title = "Start",
        snippet = FormatUtils.formatDateTime(trip.startTime),
        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
    )

    // End marker (if trip is finished)
    if (!trip.isActive && points.size > 1) {
        val endPoint = points.last()
        Marker(
            state = MarkerState(position = LatLng(endPoint.latitude, endPoint.longitude)),
            title = "End",
            snippet = trip.endTime?.let { FormatUtils.formatDateTime(it) } ?: "",
            icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
        )
    }

    // Max speed marker
    val maxSpeedPoint = points.maxByOrNull { it.speedKmh }
    maxSpeedPoint?.let { point ->
        if (point.speedKmh > 10) { // Only show if significant speed
            Marker(
                state = MarkerState(position = LatLng(point.latitude, point.longitude)),
                title = "Max Speed: ${FormatUtils.formatSpeed(point.speedKmh)}",
                snippet = FormatUtils.formatDateTime(point.timestamp),
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)
            )
        }
    }
}
