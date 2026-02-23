package com.cartracker.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cartracker.app.data.Trip
import com.cartracker.app.ui.MainViewModel
import com.cartracker.app.ui.TimeFilter
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "Your Trips",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Today's summary card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    icon = Icons.Filled.Route,
                    value = "${todayStats.tripCount}",
                    label = "Trips Today"
                )
                StatItem(
                    icon = Icons.Filled.Straighten,
                    value = FormatUtils.formatDistance(todayStats.totalDistanceMeters),
                    label = "Distance"
                )
                StatItem(
                    icon = if (isMoving) Icons.Filled.DirectionsCar else Icons.Filled.LocalParking,
                    value = if (isMoving) "Active" else "Parked",
                    label = "Status",
                    valueColor = if (isMoving) Color(0xFF4CAF50) else Color(0xFFFF9800)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Time filter
        Text(
            text = "Filter by time",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TimeFilter.entries.take(4).forEach { filter ->
                FilterChip(
                    onClick = { viewModel.setTimeFilter(filter) },
                    label = { Text(filter.label, fontSize = 12.sp) },
                    selected = timeFilter == filter,
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TimeFilter.entries.drop(4).forEach { filter ->
                FilterChip(
                    onClick = { viewModel.setTimeFilter(filter) },
                    label = { Text(filter.label, fontSize = 12.sp) },
                    selected = timeFilter == filter,
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.White
                    )
                )
            }
            // Fill remaining space
            Spacer(modifier = Modifier.weight(2f))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Trips list
        if (trips.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.DirectionsCar,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No trips in this period",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Start driving to record your first trip!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            Text(
                text = "${trips.size} trip${if (trips.size != 1) "s" else ""}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Group trips by day
                val groupedTrips = trips.groupBy { trip ->
                    FormatUtils.getRelativeDay(trip.startTime)
                }

                groupedTrips.forEach { (day, dayTrips) ->
                    item {
                        Text(
                            text = day,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    items(dayTrips) { trip ->
                        TripCard(
                            trip = trip,
                            tripNumber = trips.size - trips.indexOf(trip),
                            isCurrentTrip = trip.id == currentTripId,
                            onClick = { onTripSelected(trip.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TripCard(
    trip: Trip,
    tripNumber: Int,
    isCurrentTrip: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentTrip)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(if (isCurrentTrip) 4.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Trip number badge
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isCurrentTrip) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isCurrentTrip) {
                    Icon(
                        Icons.Filled.DirectionsCar,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Text(
                        text = "#$tripNumber",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Trip details
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isCurrentTrip) "Trip #$tripNumber (Active)" else "Trip #$tripNumber",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = FormatUtils.formatDuration(
                            if (trip.isActive) System.currentTimeMillis() - trip.startTime
                            else trip.durationMillis
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Time range
                Text(
                    text = "${FormatUtils.formatTime(trip.startTime)} → ${
                        trip.endTime?.let { FormatUtils.formatTime(it) } ?: "Now"
                    }",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Stats row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    TripStatChip(
                        icon = Icons.Filled.Straighten,
                        text = FormatUtils.formatDistance(trip.distanceMeters)
                    )
                    TripStatChip(
                        icon = Icons.Filled.Speed,
                        text = "Max: ${FormatUtils.formatSpeed(trip.maxSpeedKmh)}",
                        textColor = Color(SpeedColorUtils.getColorForSpeed(trip.maxSpeedKmh))
                    )
                    TripStatChip(
                        icon = Icons.Filled.AvTimer,
                        text = "Avg: ${FormatUtils.formatSpeed(trip.avgSpeedKmh)}"
                    )
                }
            }

            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "View trip",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun TripStatChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = textColor
        )
    }
}

@Composable
fun StatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    valueColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
    }
}
