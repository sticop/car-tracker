package com.cartracker.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.cartracker.app.CarTrackerApp
import com.cartracker.app.R
import com.cartracker.app.data.AppDatabase
import com.cartracker.app.data.LocationPoint
import com.cartracker.app.data.Trip
import com.cartracker.app.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

class LocationTrackingService : LifecycleService() {

    private lateinit var locationManager: LocationManager
    private lateinit var db: AppDatabase

    private var currentTripId: Long? = null
    private var isMoving = false
    private var lastLocation: Location? = null
    private var stationaryStartTime: Long = 0
    private var totalDistance: Double = 0.0
    private var pointCount: Int = 0
    private var speedSum: Float = 0f

    // Wake lock to keep tracking in background
    private var wakeLock: PowerManager.WakeLock? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            processLocation(location)
        }

        @Deprecated("Deprecated in API level 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // Counter for consecutive high-speed readings needed to start a trip
    private var consecutiveMovingCount = 0

    companion object {
        private const val TAG = "LocationTrackingService"

        // Speed threshold: below this = considered parked (km/h)
        // Set to 8 km/h to avoid GPS noise triggering false trips
        private const val PARKING_SPEED_THRESHOLD = 8.0f

        // Number of consecutive above-threshold readings needed to start trip
        private const val REQUIRED_MOVING_COUNT = 3

        // Minimum GPS accuracy to trust speed readings (meters)
        private const val MIN_ACCURACY_METERS = 30f

        // Time stationary before ending trip (milliseconds) - 2 minutes
        private const val PARKING_TIMEOUT_MS = 2 * 60 * 1000L

        // Location update intervals
        private const val ACTIVE_INTERVAL_MS = 3000L       // 3 seconds when moving
        private const val PASSIVE_INTERVAL_MS = 5000L       // 5 seconds when parked
        private const val FASTEST_INTERVAL_MS = 1000L       // 1 second max

        // Data retention: 30 days in milliseconds
        const val DATA_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        // SharedFlow for live updates to UI
        private val _currentSpeed = MutableStateFlow(0f)
        val currentSpeed = _currentSpeed.asStateFlow()

        private val _currentTripIdFlow = MutableStateFlow<Long?>(null)
        val currentTripIdFlow = _currentTripIdFlow.asStateFlow()

        private val _isTracking = MutableStateFlow(false)
        val isTracking = _isTracking.asStateFlow()

        private val _isMovingFlow = MutableStateFlow(false)
        val isMovingFlow = _isMovingFlow.asStateFlow()

        private val _currentLocation = MutableStateFlow<Location?>(null)
        val currentLocation = _currentLocation.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = (application as CarTrackerApp).database
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Clean old data on service start
        lifecycleScope.launch(Dispatchers.IO) {
            cleanOldData()
        }
    }

    @Suppress("MissingPermission")
    private fun getLastKnownLocation() {
        try {
            // Try GPS first, then network
            val gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            // Use the most recent one
            val bestLoc = when {
                gpsLoc != null && netLoc != null -> {
                    if (gpsLoc.time > netLoc.time) gpsLoc else netLoc
                }
                gpsLoc != null -> gpsLoc
                netLoc != null -> netLoc
                else -> null
            }

            bestLoc?.let {
                Log.d(TAG, "Last known location: ${it.latitude}, ${it.longitude} (accuracy: ${it.accuracy}m)")
                _currentLocation.value = it
                // Don't process speed from last known - it could be stale
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot get last known location", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(CarTrackerApp.TRACKING_NOTIFICATION_ID, createNotification("Monitoring for movement..."))
        acquireWakeLock()

        // Get last known location immediately so the UI has something to show
        getLastKnownLocation()

        startLocationUpdates(false) // Start in passive/parked mode
        _isTracking.value = true

        // Resume active trip if exists
        lifecycleScope.launch(Dispatchers.IO) {
            val activeTrip = db.tripDao().getActiveTrip()
            if (activeTrip != null) {
                currentTripId = activeTrip.id
                _currentTripIdFlow.value = activeTrip.id
                totalDistance = activeTrip.distanceMeters
                val pointCountVal = db.locationPointDao().getPointCountForTrip(activeTrip.id)
                pointCount = pointCountVal
                isMoving = true
                _isMovingFlow.value = true
                withContext(Dispatchers.Main) {
                    startLocationUpdates(true)
                    updateNotification("Tracking trip #${activeTrip.id}")
                }
            }
        }

        return START_STICKY // Restart if killed
    }

    private fun processLocation(location: Location) {
        val speedKmh = location.speed * 3.6f // m/s to km/h

        // Always update current location for the map, regardless of accuracy
        _currentLocation.value = location

        // Filter out inaccurate readings for speed/trip logic
        if (location.accuracy > MIN_ACCURACY_METERS) {
            Log.d(TAG, "Ignoring inaccurate location: accuracy=${location.accuracy}m, speed=$speedKmh km/h")
            // Still update speed display but don't use for trip logic
            _currentSpeed.value = speedKmh
            return
        }

        // Only trust speed if the location has speed data (hasSpeed)
        val reliableSpeed = if (location.hasSpeed()) speedKmh else 0f
        _currentSpeed.value = reliableSpeed

        if (isMoving) {
            // Currently on a trip
            if (reliableSpeed < PARKING_SPEED_THRESHOLD) {
                // Speed dropped - might be parking
                if (stationaryStartTime == 0L) {
                    stationaryStartTime = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - stationaryStartTime > PARKING_TIMEOUT_MS) {
                    // Been stationary long enough - end trip
                    endTrip()
                    return
                }
            } else {
                // Still moving - reset stationary timer
                stationaryStartTime = 0
            }

            // Record location point
            recordLocationPoint(location, reliableSpeed)

        } else {
            // Currently parked - check if starting to move
            // Require multiple consecutive readings above threshold to avoid GPS jitter
            if (reliableSpeed >= PARKING_SPEED_THRESHOLD) {
                consecutiveMovingCount++
                Log.d(TAG, "Movement detected: $reliableSpeed km/h (count: $consecutiveMovingCount/$REQUIRED_MOVING_COUNT)")
                if (consecutiveMovingCount >= REQUIRED_MOVING_COUNT) {
                    consecutiveMovingCount = 0
                    startTrip(location, reliableSpeed)
                }
            } else {
                // Reset counter if speed drops below threshold
                if (consecutiveMovingCount > 0) {
                    Log.d(TAG, "Movement reset: speed dropped to $reliableSpeed km/h")
                }
                consecutiveMovingCount = 0
            }
        }
    }

    private fun startTrip(location: Location, speedKmh: Float) {
        Log.d(TAG, "Starting new trip at speed: $speedKmh km/h")
        isMoving = true
        _isMovingFlow.value = true
        stationaryStartTime = 0
        totalDistance = 0.0
        pointCount = 0
        speedSum = 0f
        lastLocation = null

        lifecycleScope.launch(Dispatchers.IO) {
            val trip = Trip(
                startTime = System.currentTimeMillis(),
                isActive = true
            )
            val tripId = db.tripDao().insert(trip)
            currentTripId = tripId
            _currentTripIdFlow.value = tripId

            recordLocationPoint(location, speedKmh)

            withContext(Dispatchers.Main) {
                startLocationUpdates(true)
                updateNotification("Tracking trip #$tripId - ${String.format("%.0f", speedKmh)} km/h")
            }
        }
    }

    private fun endTrip() {
        Log.d(TAG, "Ending trip #$currentTripId")
        val tripId = currentTripId ?: return

        isMoving = false
        _isMovingFlow.value = false
        stationaryStartTime = 0

        lifecycleScope.launch(Dispatchers.IO) {
            val trip = db.tripDao().getTripById(tripId) ?: return@launch
            val maxSpeed = db.locationPointDao().getMaxSpeedForTrip(tripId) ?: 0f
            val avgSpeed = db.locationPointDao().getAvgSpeedForTrip(tripId) ?: 0f
            val now = System.currentTimeMillis()

            db.tripDao().update(
                trip.copy(
                    endTime = now,
                    distanceMeters = totalDistance,
                    maxSpeedKmh = maxSpeed,
                    avgSpeedKmh = avgSpeed,
                    durationMillis = now - trip.startTime,
                    isActive = false
                )
            )

            currentTripId = null
            _currentTripIdFlow.value = null

            withContext(Dispatchers.Main) {
                startLocationUpdates(false) // Switch to passive mode
                updateNotification("Parked - Monitoring for movement...")
            }
        }
    }

    private fun recordLocationPoint(location: Location, speedKmh: Float) {
        val tripId = currentTripId ?: return

        // Calculate distance from last point
        lastLocation?.let { last ->
            totalDistance += last.distanceTo(location).toDouble()
        }
        lastLocation = location
        pointCount++
        speedSum += speedKmh

        lifecycleScope.launch(Dispatchers.IO) {
            val point = LocationPoint(
                tripId = tripId,
                latitude = location.latitude,
                longitude = location.longitude,
                speed = location.speed,
                speedKmh = speedKmh,
                altitude = location.altitude,
                bearing = location.bearing,
                accuracy = location.accuracy,
                timestamp = System.currentTimeMillis()
            )
            db.locationPointDao().insert(point)

            // Update trip distance periodically
            if (pointCount % 10 == 0) {
                val trip = db.tripDao().getTripById(tripId)
                trip?.let {
                    val avgSpeed = if (pointCount > 0) speedSum / pointCount else 0f
                    db.tripDao().update(it.copy(
                        distanceMeters = totalDistance,
                        avgSpeedKmh = avgSpeed,
                        maxSpeedKmh = maxOf(it.maxSpeedKmh, speedKmh),
                        durationMillis = System.currentTimeMillis() - it.startTime
                    ))
                }
            }
        }

        // Update notification with current speed
        updateNotification("Trip #$tripId - ${String.format("%.0f", speedKmh)} km/h | ${String.format("%.1f", totalDistance / 1000)} km")
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates(activeMode: Boolean) {
        // Remove previous updates
        locationManager.removeUpdates(locationListener)

        val intervalMs = if (activeMode) ACTIVE_INTERVAL_MS else PASSIVE_INTERVAL_MS
        // Use 0 min distance in passive mode so we get updates even when stationary
        // This ensures the app gets a location fix for the map
        val minDistance = if (activeMode) 1f else 0f

        Log.d(TAG, "Starting location updates: activeMode=$activeMode interval=${intervalMs}ms minDist=${minDistance}m")

        // Try GPS provider first, then network as fallback
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    intervalMs,
                    minDistance,
                    locationListener,
                    Looper.getMainLooper()
                )
                Log.d(TAG, "GPS provider registered")
            } else {
                Log.w(TAG, "GPS provider not available")
            }

            // Also request network updates as supplement
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    intervalMs * 2, // less frequent for network
                    minDistance,
                    locationListener,
                    Looper.getMainLooper()
                )
                Log.d(TAG, "Network provider registered")
            } else {
                Log.w(TAG, "Network provider not available")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission not granted", e)
        }
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CarTrackerApp.TRACKING_CHANNEL_ID)
            .setContentTitle("Car Tracker")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_car)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(CarTrackerApp.TRACKING_NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        wakeLock?.release()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CarTracker::LocationTracking"
        ).apply {
            acquire(TimeUnit.DAYS.toMillis(365)) // Long-running
        }
    }

    private suspend fun cleanOldData() {
        val cutoff = System.currentTimeMillis() - DATA_RETENTION_MS
        db.tripDao().deleteOlderThan(cutoff)
        db.locationPointDao().deleteOlderThan(cutoff)
        Log.d(TAG, "Cleaned data older than 30 days")
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(locationListener)
        wakeLock?.release()
        _isTracking.value = false
        _isMovingFlow.value = false

        // End any active trip
        currentTripId?.let { tripId ->
            runBlocking(Dispatchers.IO) {
                val trip = db.tripDao().getTripById(tripId)
                trip?.let {
                    db.tripDao().update(it.copy(
                        endTime = System.currentTimeMillis(),
                        isActive = false,
                        distanceMeters = totalDistance,
                        durationMillis = System.currentTimeMillis() - it.startTime
                    ))
                }
            }
        }
    }
}
