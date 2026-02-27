package com.cartracker.app.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.cartracker.app.R
import com.cartracker.app.data.LocationPoint
import com.cartracker.app.data.Trip
import com.cartracker.app.map.OfflineTileManager
import com.cartracker.app.ui.MainViewModel
import com.cartracker.app.ui.TimeFilter
import com.cartracker.app.ui.theme.*
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

    val isOnline = remember { mutableStateOf(OfflineTileManager.isNetworkAvailable(context)) }
    val hasCenteredOnUser = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            isOnline.value = OfflineTileManager.isNetworkAvailable(context)
            kotlinx.coroutines.delay(5000)
        }
    }

    val carBitmap = remember {
        val size = (48 * context.resources.displayMetrics.density).toInt()
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_car_marker)
        drawable?.toBitmap(size, size)
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            @Suppress("DEPRECATION")
            setBuiltInZoomControls(false)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(48.8566, 2.3522))
            zoomController.setVisibility(
                org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
            )
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            isTilesScaledToDpi = true
            tilesScaleFactor = 1.0f
            setScrollableAreaLimitLatitude(85.05, -85.05, 0)
            setUseDataConnection(OfflineTileManager.isNetworkAvailable(context))
        }
    }

    LaunchedEffect(isOnline.value) {
        mapView.setUseDataConnection(isOnline.value)
    }

    LaunchedEffect(currentLocation) {
        if (!hasCenteredOnUser.value && currentLocation != null) {
            val loc = currentLocation!!
            mapView.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
            mapView.controller.setZoom(16.0)
            hasCenteredOnUser.value = true
        }
    }

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

    LaunchedEffect(selectedTripWithPoints, allPoints, selectedTripId) {
        mapView.overlays.clear()

        if (selectedTripId != null) {
            selectedTripWithPoints?.let { tripData ->
                drawSpeedColoredRoute(mapView, tripData.points)
                drawTripMarkers(mapView, tripData.trip, tripData.points)

                if (tripData.points.isNotEmpty()) {
                    val boundingBox = getBoundingBox(tripData.points)
                    mapView.post {
                        mapView.zoomToBoundingBox(boundingBox, true, 100)
                    }
                }
            }
        } else {
            val tripColors = listOf(
                0xFF06C167.toInt(), 0xFF276EF1.toInt(), 0xFFFFC043.toInt(),
                0xFFCB2BD5.toInt(), 0xFFE11900.toInt(), 0xFF00BCD4.toInt()
            )
            val pointsByTrip = allPoints.groupBy { it.tripId }
            pointsByTrip.entries.forEachIndexed { index, (_, points) ->
                if (points.size >= 2) {
                    val color = tripColors[index % tripColors.size]
                    val polyline = Polyline().apply {
                        outlinePaint.color = color
                        outlinePaint.strokeWidth = 10f
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.isAntiAlias = true
                        setPoints(points.map { GeoPoint(it.latitude, it.longitude) })
                    }
                    mapView.overlays.add(polyline)
                }
            }
        }

        currentLocation?.let { loc ->
            val gpsOverlay = MyLocationOverlay(
                geoPoint = GeoPoint(loc.latitude, loc.longitude),
                accuracyMeters = loc.accuracy,
                bearing = if (loc.hasBearing()) loc.bearing else null,
                isMoving = isMoving,
                carBitmap = carBitmap
            )
            mapView.overlays.add(gpsOverlay)
        }

        mapView.invalidate()
    }

    LaunchedEffect(currentLocation, isMoving) {
        currentLocation?.let { loc ->
            mapView.overlays.removeAll { it is MyLocationOverlay }

            val gpsOverlay = MyLocationOverlay(
                geoPoint = GeoPoint(loc.latitude, loc.longitude),
                accuracyMeters = loc.accuracy,
                bearing = if (loc.hasBearing()) loc.bearing else null,
                isMoving = isMoving,
                carBitmap = carBitmap
            )
            mapView.overlays.add(gpsOverlay)

            if (selectedTripId == null) {
                mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
            }

            mapView.invalidate()
        }
    }

    // ── Uber-style layout ──────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {
        // Full-screen map
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )

        // ── Top gradient fade (makes overlays readable) ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.25f),
                            Color.Transparent
                        )
                    )
                )
        )

        // ── Speed pill (top-left, Uber-style) ──
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 16.dp, top = 8.dp)
                .align(Alignment.TopStart)
                .shadow(12.dp, RoundedCornerShape(24.dp))
                .background(UberCardDark, RoundedCornerShape(24.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Animated status dot
            val pulseAlpha by rememberInfiniteTransition(label = "pulse").animateFloat(
                initialValue = 1f,
                targetValue = if (isMoving) 0.3f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseAlpha"
            )
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        (if (isMoving) UberGreen else UberOrange).copy(alpha = pulseAlpha)
                    )
            )

            Column {
                Text(
                    text = String.format("%.0f", currentSpeed),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    lineHeight = 28.sp
                )
                Text(
                    text = "km/h",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = UberTextSecondary,
                    lineHeight = 14.sp
                )
            }
        }

        // ── Status badge (top-right) ──
        Surface(
            modifier = Modifier
                .statusBarsPadding()
                .padding(end = 16.dp, top = 8.dp)
                .align(Alignment.TopEnd),
            shape = RoundedCornerShape(20.dp),
            color = if (isMoving) UberGreen.copy(alpha = 0.15f) else UberCardDark,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp
        ) {
            Text(
                text = if (isMoving) "DRIVING" else "PARKED",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = if (isMoving) UberGreen else UberTextSecondary
            )
        }

        // ── Time filter pills (below speed, horizontal scroll) ──
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 72.dp, start = 12.dp, end = 12.dp)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .align(Alignment.TopStart),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TimeFilter.entries.forEach { filter ->
                val isSelected = timeFilter == filter
                Surface(
                    modifier = Modifier.clickable {
                        viewModel.setTimeFilter(filter)
                        viewModel.selectTrip(null)
                    },
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) UberGreen else UberCardDark.copy(alpha = 0.85f),
                    shadowElevation = if (isSelected) 4.dp else 2.dp
                ) {
                    Text(
                        text = filter.label,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.Black else UberTextSecondary
                    )
                }
            }
        }

        // ── Selected trip bottom sheet ──
        AnimatedVisibility(
            visible = selectedTripWithPoints != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            selectedTripWithPoints?.let { tripData ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 0.dp),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = UberCharcoal,
                    tonalElevation = 8.dp,
                    shadowElevation = 16.dp
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        // Drag handle
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(UberDivider)
                                .align(Alignment.CenterHorizontally)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Trip header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Trip #${tripData.trip.id}",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = UberWhite
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${FormatUtils.formatDateTime(tripData.trip.startTime)} → ${
                                        tripData.trip.endTime?.let { FormatUtils.formatTime(it) } ?: "Ongoing"
                                    }",
                                    fontSize = 13.sp,
                                    color = UberTextSecondary
                                )
                            }
                            IconButton(
                                onClick = { viewModel.selectTrip(null) },
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = UberCardLight
                                )
                            ) {
                                Icon(
                                    Icons.Rounded.Close,
                                    "Close",
                                    tint = UberTextSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Stats row — Uber card style
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Distance
                            UberStatCard(
                                modifier = Modifier.weight(1f),
                                label = "Distance",
                                value = FormatUtils.formatDistance(tripData.trip.distanceMeters),
                                icon = Icons.Rounded.Route
                            )
                            // Duration
                            UberStatCard(
                                modifier = Modifier.weight(1f),
                                label = "Duration",
                                value = FormatUtils.formatDuration(tripData.trip.durationMillis),
                                icon = Icons.Rounded.Timer
                            )
                            // Max speed
                            UberStatCard(
                                modifier = Modifier.weight(1f),
                                label = "Top Speed",
                                value = FormatUtils.formatSpeed(tripData.trip.maxSpeedKmh),
                                icon = Icons.Rounded.Speed,
                                valueColor = Color(SpeedColorUtils.getColorForSpeed(tripData.trip.maxSpeedKmh))
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        // ── Bottom gradient (when no trip selected) ──
        if (selectedTripId == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.5f)
                            )
                        )
                    )
            )
        }

        // ── Trip count pill (bottom-left) ──
        if (selectedTripId == null && trips.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 20.dp),
                shape = RoundedCornerShape(20.dp),
                color = UberCardDark,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Rounded.Route,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = UberGreen
                    )
                    Text(
                        text = "${trips.size} trip${if (trips.size != 1) "s" else ""}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = UberWhite
                    )
                }
            }
        }

        // ── Offline indicator ──
        AnimatedVisibility(
            visible = !isOnline.value,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = UberRed.copy(alpha = 0.9f),
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Rounded.CloudOff,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Offline",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // ── Locating spinner ──
        if (currentLocation == null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp),
                shape = RoundedCornerShape(24.dp),
                color = UberCardDark,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = UberGreen
                    )
                    Text(
                        text = "Locating...",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = UberTextSecondary
                    )
                }
            }
        }

        // ── Re-center FAB (Uber-style rounded square) ──
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 20.dp)
                .size(48.dp)
                .clickable {
                    val loc = currentLocation
                    if (loc != null) {
                        mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
                        mapView.controller.setZoom(16.0)
                    } else {
                        Toast
                            .makeText(context, "Waiting for GPS...", Toast.LENGTH_SHORT)
                            .show()
                    }
                },
            shape = RoundedCornerShape(14.dp),
            color = UberCardDark,
            shadowElevation = 12.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.MyLocation,
                    "Center on location",
                    tint = UberGreen,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

// ── Uber-style stat card ──
@Composable
private fun UberStatCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    valueColor: Color = UberWhite
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = UberCardDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = UberGreen
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = UberTextTertiary
            )
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
 * GPS location overlay that shows a car icon when moving
 * and a blue dot when parked/stationary.
 * Includes accuracy circle, bearing rotation, and subtle glow effects.
 */
private class MyLocationOverlay(
    private val geoPoint: GeoPoint,
    private val accuracyMeters: Float,
    private val bearing: Float?,
    private val isMoving: Boolean,
    private val carBitmap: Bitmap?
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

    // Blue center dot (parked)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2196F3.toInt()
        style = Paint.Style.FILL
    }

    // Inner highlight (pre-allocated to avoid allocation in draw())
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FFFFFF
        style = Paint.Style.FILL
    }

    // Paint for drawing the car bitmap with filtering for smooth rotation
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Car shadow paint
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x30000000
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

        if (isMoving && carBitmap != null) {
            // --- MOVING: Draw car icon ---
            val bw = carBitmap.width.toFloat()
            val bh = carBitmap.height.toFloat()

            canvas.save()
            // Rotate car to face the bearing direction
            // The car icon points UP (north), so rotate by bearing degrees
            val rotation = bearing ?: 0f
            canvas.rotate(rotation, x, y)

            // Draw shadow (offset slightly down-right)
            canvas.drawOval(
                x - bw / 2f + 2f, y - bh / 2f + 4f,
                x + bw / 2f + 2f, y + bh / 2f + 4f,
                shadowPaint
            )

            // Draw car bitmap centered on the location point
            canvas.drawBitmap(
                carBitmap,
                x - bw / 2f,
                y - bh / 2f,
                bitmapPaint
            )

            canvas.restore()
        } else {
            // --- PARKED: Draw blue dot with glow ---

            // Draw outer glow
            canvas.drawCircle(x, y, 18f, glowPaint)

            // Draw white border (outer ring of dot)
            canvas.drawCircle(x, y, 14f, dotBorderPaint)

            // Draw blue center dot
            canvas.drawCircle(x, y, 11f, dotPaint)

            // Draw inner highlight (gives 3D look)
            canvas.drawCircle(x - 3f, y - 3f, 5f, highlightPaint)
        }
    }
}
