package com.cartracker.app.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.cartracker.app.data.LocationPoint
import com.cartracker.app.data.Trip
import com.cartracker.app.map.OfflineTileManager
import com.cartracker.app.ui.MainViewModel
import com.cartracker.app.ui.TimeFilter
import com.cartracker.app.util.FormatUtils
import com.cartracker.app.util.SpeedColorUtils
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

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

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Track network status for offline indicator
    val isOnline = remember { mutableStateOf(OfflineTileManager.isNetworkAvailable(context)) }

    // Periodically check network status
    LaunchedEffect(Unit) {
        while (true) {
            isOnline.value = OfflineTileManager.isNetworkAvailable(context)
            kotlinx.coroutines.delay(5000)
        }
    }

    // Create MapView - osmdroid is already configured in CarTrackerApp.onCreate()
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            @Suppress("DEPRECATION")
            setBuiltInZoomControls(false)
            controller.setZoom(15.0)
            // Default to a sensible location (center of the world)
            controller.setCenter(GeoPoint(48.8566, 2.3522)) // Paris as default
            zoomController.setVisibility(
                org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
            )
            // Enable hardware acceleration for smooth tiles
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

            // Use data connection only when online; offloads to cache when offline
            setUseDataConnection(OfflineTileManager.isNetworkAvailable(context))
        }
    }

    // Update map data connection state based on network
    LaunchedEffect(isOnline.value) {
        mapView.setUseDataConnection(isOnline.value)
    }

    // Manage MapView lifecycle
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    // Update map overlays for route data (trips/all points)
    LaunchedEffect(selectedTripWithPoints, allPoints, selectedTripId) {
        // Remove all overlays except the GPS location overlay (last one)
        mapView.overlays.clear()

        if (selectedTripId != null) {
            // Show selected trip with speed-colored segments
            selectedTripWithPoints?.let { tripData ->
                drawSpeedColoredRoute(mapView, tripData.points)
                drawTripMarkers(mapView, tripData.trip, tripData.points)

                // Zoom to fit trip
                if (tripData.points.isNotEmpty()) {
                    val boundingBox = getBoundingBox(tripData.points)
                    mapView.post {
                        mapView.zoomToBoundingBox(boundingBox, true, 100)
                    }
                }
            }
        } else {
            // Show all trips in range with different colors
            val tripColors = listOf(
                0xFF2196F3.toInt(), 0xFF4CAF50.toInt(), 0xFFFF9800.toInt(),
                0xFF9C27B0.toInt(), 0xFFE91E63.toInt(), 0xFF00BCD4.toInt()
            )
            val pointsByTrip = allPoints.groupBy { it.tripId }
            pointsByTrip.entries.forEachIndexed { index, (_, points) ->
                if (points.size >= 2) {
                    val color = tripColors[index % tripColors.size]
                    val polyline = Polyline().apply {
                        outlinePaint.color = color
                        outlinePaint.strokeWidth = 8f
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.isAntiAlias = true
                        setPoints(points.map { GeoPoint(it.latitude, it.longitude) })
                    }
                    mapView.overlays.add(polyline)
                }
            }
        }

        // Re-add current location overlay if available
        currentLocation?.let { loc ->
            val gpsOverlay = MyLocationOverlay(
                geoPoint = GeoPoint(loc.latitude, loc.longitude),
                accuracyMeters = loc.accuracy,
                bearing = if (loc.hasBearing()) loc.bearing else null,
                isMoving = isMoving
            )
            mapView.overlays.add(gpsOverlay)
        }

        mapView.invalidate()
    }

    // Update GPS location overlay only (lightweight, no route rebuild)
    LaunchedEffect(currentLocation, isMoving) {
        currentLocation?.let { loc ->
            // Remove existing GPS overlay (always the last one if present) and re-add
            mapView.overlays.removeAll { it is MyLocationOverlay }

            val gpsOverlay = MyLocationOverlay(
                geoPoint = GeoPoint(loc.latitude, loc.longitude),
                accuracyMeters = loc.accuracy,
                bearing = if (loc.hasBearing()) loc.bearing else null,
                isMoving = isMoving
            )
            mapView.overlays.add(gpsOverlay)

            // Auto-center if no trip is selected
            if (selectedTripId == null) {
                mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
            }

            mapView.invalidate()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // OSM Map
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )

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

        // Offline indicator
        if (!isOnline.value) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE65100).copy(alpha = 0.9f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Offline - Using cached maps",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        // Re-center button
        FloatingActionButton(
            onClick = {
                val loc = currentLocation
                if (loc != null) {
                    mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
                    mapView.controller.setZoom(16.0)
                } else {
                    Toast.makeText(context, "Waiting for GPS location...", Toast.LENGTH_SHORT).show()
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

private fun drawSpeedColoredRoute(mapView: MapView, points: List<LocationPoint>) {
    if (points.size < 2) return

    for (i in 0 until points.size - 1) {
        val start = points[i]
        val end = points[i + 1]
        val avgSpeed = (start.speedKmh + end.speedKmh) / 2

        val polyline = Polyline().apply {
            outlinePaint.color = SpeedColorUtils.getColorForSpeed(avgSpeed)
            outlinePaint.strokeWidth = 10f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.isAntiAlias = true
            setPoints(
                listOf(
                    GeoPoint(start.latitude, start.longitude),
                    GeoPoint(end.latitude, end.longitude)
                )
            )
        }
        mapView.overlays.add(polyline)
    }
}

private fun drawTripMarkers(mapView: MapView, trip: Trip, points: List<LocationPoint>) {
    if (points.isEmpty()) return

    // Start marker
    val startPoint = points.first()
    val startMarker = Marker(mapView).apply {
        position = GeoPoint(startPoint.latitude, startPoint.longitude)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        title = "Start"
        snippet = FormatUtils.formatDateTime(trip.startTime)
    }
    mapView.overlays.add(startMarker)

    // End marker (if trip is finished)
    if (!trip.isActive && points.size > 1) {
        val endPoint = points.last()
        val endMarker = Marker(mapView).apply {
            position = GeoPoint(endPoint.latitude, endPoint.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "End"
            snippet = trip.endTime?.let { FormatUtils.formatDateTime(it) } ?: ""
        }
        mapView.overlays.add(endMarker)
    }

    // Max speed marker
    val maxSpeedPoint = points.maxByOrNull { it.speedKmh }
    maxSpeedPoint?.let { point ->
        if (point.speedKmh > 10) {
            val maxMarker = Marker(mapView).apply {
                position = GeoPoint(point.latitude, point.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Max Speed: ${FormatUtils.formatSpeed(point.speedKmh)}"
                snippet = FormatUtils.formatDateTime(point.timestamp)
            }
            mapView.overlays.add(maxMarker)
        }
    }
}

private fun getBoundingBox(points: List<LocationPoint>): BoundingBox {
    var minLat = Double.MAX_VALUE
    var maxLat = -Double.MAX_VALUE
    var minLon = Double.MAX_VALUE
    var maxLon = -Double.MAX_VALUE

    points.forEach { point ->
        if (point.latitude < minLat) minLat = point.latitude
        if (point.latitude > maxLat) maxLat = point.latitude
        if (point.longitude < minLon) minLon = point.longitude
        if (point.longitude > maxLon) maxLon = point.longitude
    }

    val latPadding = (maxLat - minLat) * 0.1
    val lonPadding = (maxLon - minLon) * 0.1

    return BoundingBox(
        maxLat + latPadding,
        maxLon + lonPadding,
        minLat - latPadding,
        minLon - lonPadding
    )
}

/**
 * High-precision GPS location overlay.
 * Draws a blue dot with accuracy circle, bearing arrow when moving,
 * and a subtle outer glow — similar to Google Maps "my location" indicator.
 */
private class MyLocationOverlay(
    private val geoPoint: GeoPoint,
    private val accuracyMeters: Float,
    private val bearing: Float?,
    private val isMoving: Boolean
) : Overlay() {

    // Accuracy circle fill
    private val accuracyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x182196F3 // very transparent blue
        style = Paint.Style.FILL
    }

    // Accuracy circle border
    private val accuracyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x602196F3 // semi-transparent blue
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // Outer glow ring
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x402196F3
        style = Paint.Style.FILL
    }

    // White border of the dot
    private val dotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    // Blue center dot
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2196F3.toInt()
        style = Paint.Style.FILL
    }

    // Moving dot is green
    private val dotMovingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4CAF50.toInt()
        style = Paint.Style.FILL
    }

    // Bearing arrow paint
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2196F3.toInt()
        style = Paint.Style.FILL
    }

    // Inner highlight (pre-allocated to avoid allocation in draw())
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FFFFFF
        style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val projection = mapView.projection
        val screenPoint = Point()
        projection.toPixels(geoPoint, screenPoint)

        val x = screenPoint.x.toFloat()
        val y = screenPoint.y.toFloat()

        // Calculate accuracy radius in pixels
        val zoomLevel = mapView.zoomLevelDouble
        val latRad = Math.toRadians(geoPoint.latitude)
        val metersPerPixel = 156543.03392 * Math.cos(latRad) / Math.pow(2.0, zoomLevel)
        val accuracyRadiusPx = if (metersPerPixel > 0) {
            (accuracyMeters / metersPerPixel).toFloat()
        } else {
            0f
        }

        // Draw accuracy circle (only if meaningful size)
        if (accuracyRadiusPx > 20f) {
            canvas.drawCircle(x, y, accuracyRadiusPx, accuracyPaint)
            canvas.drawCircle(x, y, accuracyRadiusPx, accuracyBorderPaint)
        }

        // Draw bearing arrow when moving and bearing is available
        if (isMoving && bearing != null) {
            canvas.save()
            canvas.rotate(bearing, x, y)

            val arrowPath = Path().apply {
                // Pointing up arrow (north = 0°)
                moveTo(x, y - 36f)      // tip
                lineTo(x - 14f, y + 8f) // bottom left
                lineTo(x, y - 2f)       // inner notch
                lineTo(x + 14f, y + 8f) // bottom right
                close()
            }
            arrowPaint.color = if (isMoving) 0xFF4CAF50.toInt() else 0xFF2196F3.toInt()
            canvas.drawPath(arrowPath, arrowPaint)

            canvas.restore()
        }

        // Draw outer glow
        canvas.drawCircle(x, y, 18f, glowPaint)

        // Draw white border (outer ring of dot)
        canvas.drawCircle(x, y, 14f, dotBorderPaint)

        // Draw center dot
        val centerPaint = if (isMoving) dotMovingPaint else dotPaint
        canvas.drawCircle(x, y, 11f, centerPaint)

        // Draw inner highlight (gives 3D look)
        canvas.drawCircle(x - 3f, y - 3f, 5f, highlightPaint)
    }
}
