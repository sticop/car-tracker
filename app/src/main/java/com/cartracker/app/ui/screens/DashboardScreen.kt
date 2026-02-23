package com.cartracker.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cartracker.app.ui.MainViewModel
import com.cartracker.app.util.FormatUtils
import com.cartracker.app.util.SpeedColorUtils

@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val currentSpeed by viewModel.currentSpeed.collectAsState()
    val isMoving by viewModel.isMoving.collectAsState()
    val isTracking by viewModel.isTracking.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    val todayStats by viewModel.todayStats.collectAsState()
    val currentTripId by viewModel.currentTripId.collectAsState()
    val trips by viewModel.trips.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(
            text = "Live Dashboard",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Service status
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isTracking) Color(0xFF1B5E20).copy(alpha = 0.2f)
                else Color(0xFFB71C1C).copy(alpha = 0.2f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isTracking) Color(0xFF4CAF50) else Color(0xFFF44336))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isTracking) "Tracking Active" else "Tracking Off",
                    fontWeight = FontWeight.Medium,
                    color = if (isTracking) Color(0xFF4CAF50) else Color(0xFFF44336)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Large speedometer
        SpeedometerGauge(
            speed = currentSpeed,
            maxSpeed = 200f,
            modifier = Modifier.size(250.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Speed category
        Text(
            text = SpeedColorUtils.getSpeedCategory(currentSpeed),
            style = MaterialTheme.typography.titleMedium,
            color = Color(SpeedColorUtils.getColorForSpeed(currentSpeed)),
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Status cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoCard(
                modifier = Modifier.weight(1f),
                title = "Status",
                value = if (isMoving) "Driving" else "Parked",
                icon = if (isMoving) Icons.Filled.DirectionsCar else Icons.Filled.LocalParking,
                valueColor = if (isMoving) Color(0xFF4CAF50) else Color(0xFFFF9800)
            )
            InfoCard(
                modifier = Modifier.weight(1f),
                title = "Current Trip",
                value = currentTripId?.let { "#$it" } ?: "None",
                icon = Icons.Filled.Route,
                valueColor = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Location info
        currentLocation?.let { loc ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Current Location",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Lat: ${String.format("%.6f", loc.latitude)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Lon: ${String.format("%.6f", loc.longitude)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Alt: ${String.format("%.0f", loc.altitude)}m",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Accuracy: ${String.format("%.0f", loc.accuracy)}m",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Today's summary
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Today's Summary",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${todayStats.tripCount}",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("Trips", style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = FormatUtils.formatDistance(todayStats.totalDistanceMeters),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("Distance", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Speed proof card (for police)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1A237E).copy(alpha = 0.1f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Speed Evidence Log",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Your speed is continuously recorded with GPS precision. " +
                            "In case of a traffic stop, show this app to demonstrate your actual speed. " +
                            "Select any trip from the Trips tab to see detailed speed data at every point.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "GPS Accuracy: ${currentLocation?.accuracy?.let { "${it.toInt()}m" } ?: "N/A"}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun SpeedometerGauge(
    speed: Float,
    maxSpeed: Float,
    modifier: Modifier = Modifier
) {
    val animatedSpeed by animateFloatAsState(
        targetValue = speed,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "speed"
    )

    val speedColor = Color(SpeedColorUtils.getColorForSpeed(animatedSpeed))
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sweepAngle = 240f
            val startAngle = 150f
            val strokeWidth = 24.dp.toPx()
            val padding = strokeWidth / 2 + 8.dp.toPx()

            // Background arc
            drawArc(
                color = backgroundColor,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(padding, padding),
                size = Size(size.width - 2 * padding, size.height - 2 * padding),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Speed arc
            val speedSweep = (animatedSpeed / maxSpeed).coerceIn(0f, 1f) * sweepAngle
            drawArc(
                color = speedColor,
                startAngle = startAngle,
                sweepAngle = speedSweep,
                useCenter = false,
                topLeft = Offset(padding, padding),
                size = Size(size.width - 2 * padding, size.height - 2 * padding),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        // Speed text
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = String.format("%.0f", animatedSpeed),
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = speedColor
            )
            Text(
                text = "km/h",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = valueColor,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                color = valueColor,
                textAlign = TextAlign.Center
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
