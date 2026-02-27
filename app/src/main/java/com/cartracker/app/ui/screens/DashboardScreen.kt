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
import androidx.compose.material.icons.rounded.*
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
import com.cartracker.app.ui.theme.*
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
            .background(UberBlack)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Header
        Text(
            text = "Live",
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            color = UberWhite,
            letterSpacing = (-0.5).sp,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Tracking status pill
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = if (isTracking) UberGreen.copy(alpha = 0.1f) else UberRed.copy(alpha = 0.1f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                val pulseAlpha by rememberInfiniteTransition(label = "trackPulse").animateFloat(
                    initialValue = 1f,
                    targetValue = 0.3f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "trackPulseAlpha"
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            (if (isTracking) UberGreen else UberRed).copy(alpha = pulseAlpha)
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isTracking) "TRACKING ACTIVE" else "TRACKING OFF",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                    color = if (isTracking) UberGreen else UberRed
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Uber-style speedometer
        UberSpeedometer(
            speed = currentSpeed,
            maxSpeed = 200f,
            modifier = Modifier.size(260.dp)
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Speed category
        Text(
            text = SpeedColorUtils.getSpeedCategory(currentSpeed),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = Color(SpeedColorUtils.getColorForSpeed(currentSpeed))
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Status cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            UberDashCard(
                modifier = Modifier.weight(1f),
                title = "Status",
                value = if (isMoving) "Driving" else "Parked",
                icon = if (isMoving) Icons.Rounded.DirectionsCar else Icons.Rounded.LocalParking,
                accentColor = if (isMoving) UberGreen else UberOrange
            )
            UberDashCard(
                modifier = Modifier.weight(1f),
                title = "Current Trip",
                value = currentTripId?.let { "#$it" } ?: "None",
                icon = Icons.Rounded.Route,
                accentColor = UberGreen
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Location info
        currentLocation?.let { loc ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = UberCardDark
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.MyLocation,
                            contentDescription = null,
                            tint = UberGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Location",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = UberWhite
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            UberLocLabel("Lat", String.format("%.6f", loc.latitude))
                            Spacer(modifier = Modifier.height(4.dp))
                            UberLocLabel("Lon", String.format("%.6f", loc.longitude))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            UberLocLabel("Alt", "${String.format("%.0f", loc.altitude)}m")
                            Spacer(modifier = Modifier.height(4.dp))
                            UberLocLabel("Acc", "${String.format("%.0f", loc.accuracy)}m")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Today's summary
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = UberCardDark
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Today",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = UberWhite
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${todayStats.tripCount}",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            color = UberGreen
                        )
                        Text("Trips", fontSize = 11.sp, color = UberTextTertiary)
                    }
                    // Divider
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(48.dp)
                            .background(UberDivider)
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = FormatUtils.formatDistance(todayStats.totalDistanceMeters),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            color = UberGreen
                        )
                        Text("Distance", fontSize = 11.sp, color = UberTextTertiary)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Speed evidence card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = UberBlue.copy(alpha = 0.08f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = UberBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Speed Evidence",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = UberWhite
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Speed is recorded with GPS precision. Show this app during a traffic stop to prove your actual speed. View trip details for point-by-point data.",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = UberTextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = UberCardDark
                ) {
                    Text(
                        text = "GPS Accuracy: ${currentLocation?.accuracy?.let { "${it.toInt()}m" } ?: "N/A"}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = UberBlue
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun UberLocLabel(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = UberTextTertiary)
        Text(text = value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = UberWhite)
    }
}

// ── Uber-style Speedometer ──
@Composable
fun UberSpeedometer(
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

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sweepAngle = 240f
            val startAngle = 150f
            val strokeWidth = 20.dp.toPx()
            val padding = strokeWidth / 2 + 8.dp.toPx()

            // Background arc — dark
            drawArc(
                color = UberCardLight,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(padding, padding),
                size = Size(size.width - 2 * padding, size.height - 2 * padding),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Speed arc — green/colored
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

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = String.format("%.0f", animatedSpeed),
                fontSize = 60.sp,
                fontWeight = FontWeight.Black,
                color = UberWhite,
                letterSpacing = (-2).sp
            )
            Text(
                text = "km/h",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = UberTextSecondary
            )
        }
    }
}

@Composable
private fun UberDashCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color = UberGreen
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = UberCardDark
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
                tint = accentColor,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = accentColor,
                textAlign = TextAlign.Center
            )
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = UberTextTertiary
            )
        }
    }
}

// Keep InfoCard for backward compat
@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    valueColor: Color = UberWhite
) {
    UberDashCard(modifier = modifier, title = title, value = value, icon = icon, accentColor = valueColor)
}
