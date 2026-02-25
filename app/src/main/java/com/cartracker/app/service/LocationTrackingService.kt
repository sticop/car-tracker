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
import com.cartracker.app.map.OfflineTileManager
import com.cartracker.app.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.BroadcastReceiver
import android.content.IntentFilter

class LocationTrackingService : LifecycleService() {

    private lateinit var locationManager: LocationManager
    private lateinit var db: AppDatabase

    private var currentTripId: Long? = null
    private var isMoving = false
    private var lastLocation: Location? = null      // For speed calculation
    private var lastLocationTime: Long = 0
    private var lastTripLocation: Location? = null   // For trip distance calculation
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
        // Set to 5 km/h — low enough to detect real movement, high enough to filter GPS jitter
        private const val PARKING_SPEED_THRESHOLD = 5.0f

        // Number of consecutive above-threshold readings needed to start trip
        private const val REQUIRED_MOVING_COUNT = 3

        // Minimum GPS accuracy to trust speed readings (meters)
        // Relaxed to 50m to work with older GPS hardware (e.g. Galaxy S6)
        private const val MIN_ACCURACY_METERS = 50f

        // Maximum realistic speed for a car (km/h) - anything above is GPS glitch
        private const val MAX_REALISTIC_SPEED = 300f

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

        /**
         * Called from MainActivity to push a location fix directly,
         * allowing the map to show user's position before the service fully starts.
         */
        fun updateLocationFromActivity(location: Location) {
            if (_currentLocation.value == null ||
                location.time > (_currentLocation.value?.time ?: 0)) {
                _currentLocation.value = location
            }
        }
    }

    // When true, suppresses real GPS updates (for mock/simulation testing)
    private var mockModeActive = false

    // Debug mock location receiver for simulation testing
    private val mockLocationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.cartracker.app.MOCK_LOCATION") {
                // First mock location: disable real GPS to prevent interference
                if (!mockModeActive) {
                    mockModeActive = true
                    locationManager.removeUpdates(locationListener)
                    Log.d(TAG, "Mock mode activated - real GPS disabled")
                }
                // Use string extras for maximum compatibility with adb
                val lat = intent.getStringExtra("lat")?.toDoubleOrNull() ?: 0.0
                val lon = intent.getStringExtra("lon")?.toDoubleOrNull() ?: 0.0
                val mockLocation = Location("mock").apply {
                    latitude = lat
                    longitude = lon
                    accuracy = 5f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                Log.d(TAG, "Mock location received: $lat, $lon")
                processLocation(mockLocation)
            }
        }
    }
    private var mockReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        db = (application as CarTrackerApp).database
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Register debug mock location receiver
        try {
            val filter = IntentFilter("com.cartracker.app.MOCK_LOCATION")
            registerReceiver(mockLocationReceiver, filter)
            mockReceiverRegistered = true
            Log.d(TAG, "Mock location receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register mock receiver", e)
        }

        // Clean old data on service start
        lifecycleScope.launch(Dispatchers.IO) {
            cleanOldData()
        }
    }

    private fun getLastKnownLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Location permission not granted for last known location")
            return
        }
        try {
            // Try ALL providers for the best chance of getting a fix
            val gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val passiveLoc = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)

            // Pick the most recent location from any provider
            val candidates = listOfNotNull(gpsLoc, netLoc, passiveLoc)
            val bestLoc = candidates.maxByOrNull { it.time }

            if (bestLoc != null) {
                Log.d(TAG, "Last known location: ${bestLoc.latitude}, ${bestLoc.longitude} (provider: ${bestLoc.provider}, accuracy: ${bestLoc.accuracy}m)")
                _currentLocation.value = bestLoc
            } else {
                Log.w(TAG, "No last known location from any provider")
                // Request a single immediate fix from GPS
                requestImmediateLocationFix()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot get last known location", e)
        }
    }

    /**
     * Request a single immediate location fix from all available providers.
     * This is faster than periodic updates for getting the first fix.
     */
    @Suppress("MissingPermission")
    private fun requestImmediateLocationFix() {
        Log.d(TAG, "Requesting immediate location fix...")
        val singleUpdateListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                Log.d(TAG, "Immediate fix received: ${location.latitude}, ${location.longitude} (${location.provider})")
                if (_currentLocation.value == null) {
                    _currentLocation.value = location
                }
                // Don't remove - let periodic updates take over
            }
            @Deprecated("Deprecated in API level 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            // Request from all providers for fastest possible fix
            val providers = locationManager.getProviders(true)
            for (provider in providers) {
                try {
                    locationManager.requestSingleUpdate(provider, singleUpdateListener, Looper.getMainLooper())
                    Log.d(TAG, "Requested single update from: $provider")
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot request single update from $provider: ${e.message}")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for immediate fix", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(CarTrackerApp.TRACKING_NOTIFICATION_ID, createNotification("Monitoring for movement..."))
        acquireWakeLock()

        // Get last known location immediately so the UI has something to show
        getLastKnownLocation()

        // Also request an immediate single fix for fastest response
        requestImmediateLocationFix()

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
        // Always update current location for the map, regardless of accuracy
        _currentLocation.value = location

        // Cache map tiles around current location for offline use
        OfflineTileManager.cacheTilesAroundLocation(
            this, location.latitude, location.longitude, lifecycleScope
        )

        // Filter out very inaccurate readings for speed/trip logic
        if (location.accuracy > MIN_ACCURACY_METERS) {
            Log.d(TAG, "Ignoring inaccurate location: accuracy=${location.accuracy}m")
            return
        }

        // Calculate speed - use device speed if available, otherwise compute from distance
        val speedKmh: Float
        if (location.hasSpeed() && location.speed > 0f) {
            speedKmh = location.speed * 3.6f // m/s to km/h
            Log.d(TAG, "GPS speed: $speedKmh km/h (hasSpeed=true)")
        } else {
            // Many older devices (e.g. Galaxy S6) don't provide speed via LocationManager
            // Calculate speed from distance between consecutive fixes
            val prevLoc = lastLocation
            val prevTime = lastLocationTime
            if (prevLoc != null && prevTime > 0) {
                val distanceM = prevLoc.distanceTo(location).toDouble()
                val timeDiffSec = (location.time - prevTime) / 1000.0
                if (timeDiffSec > 0.5 && timeDiffSec < 30.0) {
                    // Only compute speed if time gap is reasonable (0.5s to 30s)
                    val speedMs = distanceM / timeDiffSec
                    speedKmh = (speedMs * 3.6).toFloat()
                    Log.d(TAG, "Computed speed: $speedKmh km/h (dist=${String.format("%.1f", distanceM)}m, dt=${String.format("%.1f", timeDiffSec)}s)")
                } else {
                    speedKmh = 0f
                    Log.d(TAG, "Time gap too large/small for speed calc: ${timeDiffSec}s")
                }
            } else {
                speedKmh = 0f
                Log.d(TAG, "No previous location for speed calculation")
            }
        }

        // Sanity check: discard impossible speeds (GPS glitch / provider switching)
        if (speedKmh > MAX_REALISTIC_SPEED) {
            Log.w(TAG, "Ignoring unrealistic speed: $speedKmh km/h (max: $MAX_REALISTIC_SPEED)")
            // Don't update lastLocation — treat this reading as garbage
            return
        }

        // Update last location for next speed calculation (before trip logic)
        lastLocation = location
        lastLocationTime = location.time

        _currentSpeed.value = speedKmh

        if (isMoving) {
            // Currently on a trip
            if (speedKmh < PARKING_SPEED_THRESHOLD) {
                // Speed dropped - might be parking
                if (stationaryStartTime == 0L) {
                    stationaryStartTime = System.currentTimeMillis()
                    Log.d(TAG, "Stationary timer started")
                } else {
                    val elapsed = System.currentTimeMillis() - stationaryStartTime
                    if (elapsed > PARKING_TIMEOUT_MS) {
                        // Been stationary long enough - end trip
                        Log.d(TAG, "Parking timeout reached (${elapsed}ms) - ending trip")
                        endTrip()
                        return
                    } else {
                        Log.d(TAG, "Stationary for ${elapsed / 1000}s / ${PARKING_TIMEOUT_MS / 1000}s")
                    }
                }
            } else {
                // Still moving - reset stationary timer
                if (stationaryStartTime > 0) {
                    Log.d(TAG, "Movement resumed - stationary timer reset")
                }
                stationaryStartTime = 0
            }

            // Record location point
            recordLocationPoint(location, speedKmh)

        } else {
            // Currently parked - check if starting to move
            // Require multiple consecutive readings above threshold to avoid GPS jitter
            if (speedKmh >= PARKING_SPEED_THRESHOLD) {
                consecutiveMovingCount++
                Log.d(TAG, "Movement detected: $speedKmh km/h (count: $consecutiveMovingCount/$REQUIRED_MOVING_COUNT)")
                if (consecutiveMovingCount >= REQUIRED_MOVING_COUNT) {
                    consecutiveMovingCount = 0
                    startTrip(location, speedKmh)
                }
            } else {
                // Reset counter if speed drops below threshold
                if (consecutiveMovingCount > 0) {
                    Log.d(TAG, "Movement reset: speed dropped to $speedKmh km/h")
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
        lastTripLocation = null

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

        // Calculate distance from last trip point
        lastTripLocation?.let { last ->
            totalDistance += last.distanceTo(location).toDouble()
        }
        lastTripLocation = location
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

    private fun startLocationUpdates(activeMode: Boolean) {
        // Skip real GPS registration if mock mode is active (simulation testing)
        if (mockModeActive) {
            Log.d(TAG, "Mock mode active - skipping real GPS registration")
            return
        }

        // Check permission at runtime
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Location permission not granted, cannot start updates")
            return
        }

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
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CarTracker::LocationTracking"
        ).apply {
            acquire(TimeUnit.HOURS.toMillis(6)) // Re-acquired on each onStartCommand
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
        // Unregister mock location receiver
        if (mockReceiverRegistered) {
            try { unregisterReceiver(mockLocationReceiver) } catch (_: Exception) {}
            mockReceiverRegistered = false
        }
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        _isTracking.value = false
        _isMovingFlow.value = false
        _currentSpeed.value = 0f
        _currentLocation.value = null
        _currentTripIdFlow.value = null

        // End any active trip using a non-blocking approach
        currentTripId?.let { tripId ->
            val dist = totalDistance
            val appDb = db
            // Use a GlobalScope coroutine so it survives service destruction
            // This is one of the rare valid uses of GlobalScope
            @Suppress("OPT_IN_USAGE")
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val trip = appDb.tripDao().getTripById(tripId)
                    trip?.let {
                        appDb.tripDao().update(it.copy(
                            endTime = System.currentTimeMillis(),
                            isActive = false,
                            distanceMeters = dist,
                            durationMillis = System.currentTimeMillis() - it.startTime
                        ))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error ending trip on destroy", e)
                }
            }
        }
    }
}
