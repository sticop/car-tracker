package com.cartracker.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cartracker.app.CarTrackerApp
import com.cartracker.app.data.AppDatabase
import com.cartracker.app.data.LocationPoint
import com.cartracker.app.data.Trip
import com.cartracker.app.service.LocationTrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class TimeFilter(val label: String, val hours: Int) {
    LAST_1H("1h", 1),
    LAST_6H("6h", 6),
    LAST_24H("24h", 24),
    LAST_3D("3 days", 72),
    LAST_7D("7 days", 168),
    LAST_30D("30 days", 720)
}

data class TripWithPoints(
    val trip: Trip,
    val points: List<LocationPoint>
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db: AppDatabase = (application as CarTrackerApp).database

    // Time filter
    private val _timeFilter = MutableStateFlow(TimeFilter.LAST_24H)
    val timeFilter = _timeFilter.asStateFlow()

    // Selected trip (null = show all trips in filter range)
    private val _selectedTripId = MutableStateFlow<Long?>(null)
    val selectedTripId = _selectedTripId.asStateFlow()

    // Trips list
    val trips: StateFlow<List<Trip>> = _timeFilter.flatMapLatest { filter ->
        val since = System.currentTimeMillis() - (filter.hours * 3600 * 1000L)
        db.tripDao().getTripsSince(since)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Selected trip with points
    val selectedTripWithPoints: StateFlow<TripWithPoints?> = _selectedTripId.flatMapLatest { tripId ->
        if (tripId != null) {
            combine(
                db.tripDao().getTripByIdFlow(tripId),
                db.locationPointDao().getPointsForTrip(tripId)
            ) { trip, points ->
                trip?.let { TripWithPoints(it, points) }
            }
        } else {
            flowOf(null)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // All points for the selected time range (when no specific trip is selected)
    val allPointsInRange: StateFlow<List<LocationPoint>> = _timeFilter.flatMapLatest { filter ->
        val since = System.currentTimeMillis() - (filter.hours * 3600 * 1000L)
        db.locationPointDao().getPointsSince(since)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Live tracking data from service
    val currentSpeed = LocationTrackingService.currentSpeed
    val isTracking = LocationTrackingService.isTracking
    val isMoving = LocationTrackingService.isMovingFlow
    val currentLocation = LocationTrackingService.currentLocation
    val currentTripId = LocationTrackingService.currentTripIdFlow

    // Stats
    private val _todayStats = MutableStateFlow(DayStats())
    val todayStats = _todayStats.asStateFlow()

    init {
        // Load today's stats
        viewModelScope.launch(Dispatchers.IO) {
            loadTodayStats()
        }
    }

    fun setTimeFilter(filter: TimeFilter) {
        _timeFilter.value = filter
    }

    fun selectTrip(tripId: Long?) {
        _selectedTripId.value = tripId
    }

    private suspend fun loadTodayStats() {
        val todayStart = System.currentTimeMillis() - (24 * 3600 * 1000L)
        val tripCount = db.tripDao().getTripCountSince(todayStart)
        val totalDistance = db.tripDao().getTotalDistanceSince(todayStart) ?: 0.0

        _todayStats.value = DayStats(
            tripCount = tripCount,
            totalDistanceMeters = totalDistance
        )
    }

    fun refreshStats() {
        viewModelScope.launch(Dispatchers.IO) {
            loadTodayStats()
        }
    }

    suspend fun getTripPoints(tripId: Long): List<LocationPoint> {
        return db.locationPointDao().getPointsForTripSync(tripId)
    }
}

data class DayStats(
    val tripCount: Int = 0,
    val totalDistanceMeters: Double = 0.0
)
