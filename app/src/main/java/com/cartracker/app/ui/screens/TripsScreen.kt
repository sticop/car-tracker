package com.cartracker.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cartracker.app.data.Trip
import com.cartracker.app.ui.MainViewModel
import com.cartracker.app.ui.TimeFilter
import com.cartracker.app.ui.theme.*
import com.cartracker.app.util.FormatUtils
import com.cartracker.app.util.SpeedColorUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(
    viewModel: MainViewModel,
    onTripSelected: (Long) -> Unit
) {
    val trips by viewModel.trips.collectAsState()
    val timeFilter by viewModel.timeFilter.collectAsState()
    val todayStats by viewModel.todayStats.collectAsState()
    val isMoving by viewModel.isMoving.collectAsState()
    val currentTripId by viewModel.currentTripId.collectAsState()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        UberBlack,
                        UberDarkGray.copy(alpha = 0.95f),
                        UberBlack
                    )
                )
            )
    ) {
        val isTablet = maxWidth >= 840.dp
        val horizontalPadding = if (isTablet) 32.dp else 20.dp
        val maxContentWidth = if (isTablet) 1100.dp else 700.dp
        val sectionSpacing = if (isTablet) 20.dp else 16.dp
        val cardSpacing = if (isTablet) 14.dp else 10.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = horizontalPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = maxContentWidth)
                    .align(Alignment.CenterHorizontally)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Your Trips",
                    fontSize = if (isTablet) 32.sp else 28.sp,
                    fontWeight = FontWeight.Black,
                    color = UberWhite,
                    letterSpacing = (-0.5).sp
                )

                Spacer(modifier = Modifier.height(sectionSpacing))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(cardSpacing)
                ) {
                    UberMiniStat(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Route,
                        value = "${todayStats.tripCount}",
                        label = "Today"
                    )
                    UberMiniStat(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Straighten,
                        value = FormatUtils.formatDistance(todayStats.totalDistanceMeters),
                        label = "Distance"
                    )
                    UberMiniStat(
                        modifier = Modifier.weight(1f),
                        icon = if (isMoving) Icons.Rounded.DirectionsCar else Icons.Rounded.LocalParking,
                        value = if (isMoving) "Active" else "Parked",
                        label = "Status",
                        accentColor = if (isMoving) UberGreen else UberOrange
                    )
                }

                Spacer(modifier = Modifier.height(sectionSpacing))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TimeFilter.entries.forEach { filter ->
                        val isSelected = timeFilter == filter
                        Surface(
                            modifier = Modifier.clickable { viewModel.setTimeFilter(filter) },
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) UberGreen else UberCardDark
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

                Spacer(modifier = Modifier.height(sectionSpacing))

                if (trips.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                shape = CircleShape,
                                color = UberCardDark,
                                modifier = Modifier.size(if (isTablet) 92.dp else 80.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Rounded.DirectionsCar,
                                        contentDescription = null,
                                        modifier = Modifier.size(if (isTablet) 40.dp else 36.dp),
                                        tint = UberTextTertiary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No trips yet",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = UberWhite
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Start driving to see your trips here",
                                fontSize = 13.sp,
                                color = UberTextTertiary
                            )
                        }
                    }
                } else {
                    Text(
                        text = "${trips.size} trip${if (trips.size != 1) "s" else ""}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = UberTextTertiary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(if (isTablet) 4.dp else 2.dp),
                        contentPadding = PaddingValues(bottom = 20.dp)
                    ) {
                        val groupedTrips = trips.groupBy { trip ->
                            FormatUtils.getRelativeDay(trip.startTime)
                        }

                        groupedTrips.forEach { (day, dayTrips) ->
                            item {
                                Text(
                                    text = day.uppercase(),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    color = UberGreen,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                                )
                            }

                            items(
                                items = dayTrips,
                                key = { trip -> trip.id }
                            ) { trip ->
                                val tripIndex = trips.indexOf(trip)
                                UberTripCard(
                                    trip = trip,
                                    tripNumber = trips.size - tripIndex,
                                    isCurrentTrip = trip.id == currentTripId,
                                    onClick = { onTripSelected(trip.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UberTripCard(
    trip: Trip,
    tripNumber: Int,
    isCurrentTrip: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isCurrentTrip) UberGreen.copy(alpha = 0.08f) else UberCardDark,
        animationSpec = tween(300),
        label = "tripBg"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Trip number badge
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isCurrentTrip) UberGreen
                        else UberCardLight
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isCurrentTrip) {
                    Icon(
                        Icons.Rounded.DirectionsCar,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Text(
                        text = "#$tripNumber",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = UberWhite
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isCurrentTrip) "Trip #$tripNumber" else "Trip #$tripNumber",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = UberWhite
                    )
                    if (isCurrentTrip) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = UberGreen.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "LIVE",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = UberGreen
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = "${FormatUtils.formatTime(trip.startTime)} → ${
                        trip.endTime?.let { FormatUtils.formatTime(it) } ?: "Now"
                    }",
                    fontSize = 13.sp,
                    color = UberTextSecondary
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Stats — inline pills
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    UberStatPill(
                        text = FormatUtils.formatDistance(trip.distanceMeters),
                        icon = Icons.Rounded.Straighten
                    )
                    UberStatPill(
                        text = FormatUtils.formatSpeed(trip.maxSpeedKmh),
                        icon = Icons.Rounded.Speed,
                        textColor = Color(SpeedColorUtils.getColorForSpeed(trip.maxSpeedKmh))
                    )
                    UberStatPill(
                        text = FormatUtils.formatDuration(
                            if (trip.isActive) System.currentTimeMillis() - trip.startTime
                            else trip.durationMillis
                        ),
                        icon = Icons.Rounded.Timer
                    )
                }
            }

            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = "View trip",
                tint = UberTextTertiary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun UberStatPill(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    textColor: Color = UberTextSecondary
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = UberTextTertiary
        )
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

@Composable
private fun UberMiniStat(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
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
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = UberWhite
            )
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = UberTextTertiary
            )
        }
    }
}

// Keep StatItem for backward compat (used elsewhere?)
@Composable
fun StatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    valueColor: Color = UberWhite
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = UberGreen, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = valueColor)
        Text(text = label, fontSize = 11.sp, color = UberTextTertiary)
    }
}
