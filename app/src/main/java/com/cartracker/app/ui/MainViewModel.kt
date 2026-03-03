package com.cartracker.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cartracker.app.CarTrackerApp
import com.cartracker.app.data.AppDatabase
import com.cartracker.app.data.LocationPoint
import com.cartracker.app.data.Trip
import com.cartracker.app.map.CachedCityMap
import com.cartracker.app.map.CityMapCacheStore
import com.cartracker.app.map.OfflineTileManager
import com.cartracker.app.settings.AppSettings
import com.cartracker.app.settings.MapStylePreference
import com.cartracker.app.service.LocationTrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

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

data class CityDownloadProgress(
    val cityName: String,
    val completedTiles: Int,
    val totalTiles: Int,
    val stage: String
) {
    val fraction: Float
        get() = if (totalTiles > 0) completedTiles.toFloat() / totalTiles else 0f
}

data class CityDownloadPrompt(
    val region: OfflineTileManager.CityRegion
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CarTrackerApp
    private val db: AppDatabase = app.database
    private val settingsStore = app.settingsStore
    private val appContext = application.applicationContext

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

    // Offline city map settings state
    private val _cachedCityMaps = MutableStateFlow<List<CachedCityMap>>(emptyList())
    val cachedCityMaps = _cachedCityMaps.asStateFlow()

    private val _mapCacheSizeMb = MutableStateFlow(0.0)
    val mapCacheSizeMb = _mapCacheSizeMb.asStateFlow()

    private val _cityDownloadProgress = MutableStateFlow<CityDownloadProgress?>(null)
    val cityDownloadProgress = _cityDownloadProgress.asStateFlow()

    private val _cityDownloadPrompt = MutableStateFlow<CityDownloadPrompt?>(null)
    val cityDownloadPrompt = _cityDownloadPrompt.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage = _statusMessage.asStateFlow()

    val appSettings: StateFlow<AppSettings> = settingsStore.settings

    private var activeDownloadCityId: String? = null
    private var lastPromptedCityId: String? = null
    private var lastPromptCheckAtMs: Long = 0L

    init {
        viewModelScope.launch(Dispatchers.IO) {
            loadTodayStats()
            refreshOfflineMapStatsInternal()
        }
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                if (!settings.offlineCityPromptEnabled) {
                    _cityDownloadPrompt.value = null
                }
            }
        }
        observeCityPromptSuggestions()
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

    fun refreshOfflineMapStats() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshOfflineMapStatsInternal()
        }
    }

    private fun observeCityPromptSuggestions() {
        viewModelScope.launch {
            currentLocation
                .filterNotNull()
                .collect { location ->
                    if (!settingsStore.settings.value.offlineCityPromptEnabled) return@collect

                    val now = System.currentTimeMillis()
                    if (now - lastPromptCheckAtMs < 60_000L) return@collect
                    lastPromptCheckAtMs = now

                    if (_cityDownloadPrompt.value != null || activeDownloadCityId != null) return@collect
                    if (!OfflineTileManager.isNetworkAvailable(appContext)) return@collect

                    val region = withContext(Dispatchers.IO) {
                        OfflineTileManager.reverseGeocodeCityRegion(location.latitude, location.longitude)
                    } ?: return@collect

                    val cityId = CityMapCacheStore.cityIdFor(region.name)
                    if (cityId == lastPromptedCityId) return@collect
                    lastPromptedCityId = cityId

                    if (!CityMapCacheStore.canPromptForCity(appContext, region.name)) return@collect

                    _cityDownloadPrompt.value = CityDownloadPrompt(region)
                }
        }
    }

    fun downloadCityMapByName(query: String) {
        val cityQuery = query.trim()
        if (cityQuery.isBlank()) {
            postStatus("Enter a city name first")
            return
        }
        if (activeDownloadCityId != null) {
            postStatus("Another map download is already running")
            return
        }

        viewModelScope.launch {
            val region = withContext(Dispatchers.IO) {
                OfflineTileManager.searchCityRegion(cityQuery)
            }

            if (region == null) {
                postStatus("City not found: $cityQuery")
                return@launch
            }

            startCityDownload(region)
        }
    }

    fun downloadCityMapForCurrentLocation() {
        val location = currentLocation.value
        if (location == null) {
            postStatus("Current location is not available yet")
            return
        }
        if (activeDownloadCityId != null) {
            postStatus("Another map download is already running")
            return
        }

        viewModelScope.launch {
            val region = withContext(Dispatchers.IO) {
                OfflineTileManager.reverseGeocodeCityRegion(location.latitude, location.longitude)
            }

            if (region == null) {
                postStatus("Could not resolve city from current location")
                return@launch
            }

            startCityDownload(region)
        }
    }

    fun downloadPromptedCityMap() {
        val prompt = _cityDownloadPrompt.value ?: return
        if (activeDownloadCityId != null) {
            postStatus("Another map download is already running")
            return
        }

        _cityDownloadPrompt.value = null
        viewModelScope.launch {
            startCityDownload(prompt.region)
        }
    }

    fun deferCityDownloadPrompt() {
        val prompt = _cityDownloadPrompt.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            CityMapCacheStore.deferPrompt(appContext, prompt.region.name)
            _cityDownloadPrompt.value = null
        }
    }

    fun removeCachedCity(cityId: String) {
        if (activeDownloadCityId != null) {
            postStatus("Wait until the current download finishes")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val removed = CityMapCacheStore.removeCachedCity(appContext, cityId) ?: return@launch
            OfflineTileManager.deleteCityTiles(appContext, removed.toRegion())
            refreshOfflineMapStatsInternal()
            postStatus("Removed offline map: ${removed.cityName}")
        }
    }

    fun redownloadCachedCity(cityId: String) {
        val city = _cachedCityMaps.value.firstOrNull { it.id == cityId } ?: return
        if (activeDownloadCityId != null) {
            postStatus("Another map download is already running")
            return
        }

        viewModelScope.launch {
            startCityDownload(city.toRegion())
        }
    }

    fun clearAllCachedCityMaps() {
        if (activeDownloadCityId != null) {
            postStatus("Wait until the current download finishes")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val cities = CityMapCacheStore.getCachedCities(appContext)
            cities.forEach { city ->
                OfflineTileManager.deleteCityTiles(appContext, city.toRegion())
            }
            CityMapCacheStore.clearCachedCities(appContext)
            refreshOfflineMapStatsInternal()
            postStatus("Removed ${cities.size} city offline map(s)")
        }
    }

    private suspend fun startCityDownload(region: OfflineTileManager.CityRegion) {
        if (!OfflineTileManager.isNetworkAvailable(appContext)) {
            postStatus("No internet connection")
            return
        }

        val normalizedRegion = region.normalized()
        val cityId = CityMapCacheStore.cityIdFor(normalizedRegion.name)

        activeDownloadCityId = cityId
        val estimatedTiles = max(1, OfflineTileManager.estimateCityTileCount(normalizedRegion))
        _cityDownloadProgress.value = CityDownloadProgress(
            cityName = normalizedRegion.name,
            completedTiles = 0,
            totalTiles = estimatedTiles,
            stage = "Preparing download"
        )

        try {
            val result = withContext(Dispatchers.IO) {
                OfflineTileManager.downloadCityTiles(appContext, normalizedRegion) { completed, total, stage ->
                    _cityDownloadProgress.value = CityDownloadProgress(
                        cityName = normalizedRegion.name,
                        completedTiles = completed,
                        totalTiles = total,
                        stage = stage
                    )
                }
            }

            if (result.success) {
                CityMapCacheStore.upsertCachedCity(
                    context = appContext,
                    region = normalizedRegion,
                    tileCount = result.totalTiles
                )
                refreshOfflineMapStatsInternal()
                CityMapCacheStore.clearPromptCooldown(appContext, normalizedRegion.name)
                postStatus("Offline map ready for ${normalizedRegion.name}")
            } else {
                postStatus(result.errorMessage ?: "Failed to download ${normalizedRegion.name}")
            }
        } catch (e: Exception) {
            postStatus("Download failed: ${e.message ?: "unknown error"}")
        } finally {
            activeDownloadCityId = null
            _cityDownloadProgress.value = null
        }
    }

    private fun refreshOfflineMapStatsInternal() {
        _cachedCityMaps.value = CityMapCacheStore.getCachedCities(appContext)
        _mapCacheSizeMb.value = OfflineTileManager.getCacheSizeMB(appContext)
    }

    private fun postStatus(message: String) {
        _statusMessage.value = message
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun setDefaultMapStyle(value: MapStylePreference) {
        settingsStore.setDefaultMapStyle(value)
    }

    fun setAutoCenterMap(value: Boolean) {
        settingsStore.setAutoCenterMap(value)
    }

    fun setOfflineCityPromptEnabled(value: Boolean) {
        settingsStore.setOfflineCityPromptEnabled(value)
    }

    fun setAutoTileCacheEnabled(value: Boolean) {
        settingsStore.setAutoTileCacheEnabled(value)
    }

    fun setWebSyncEnabled(value: Boolean) {
        settingsStore.setWebSyncEnabled(value)
    }

    fun setBatterySaverModeEnabled(value: Boolean) {
        settingsStore.setBatterySaverModeEnabled(value)
    }

    fun setAutoStartOnBootEnabled(value: Boolean) {
        settingsStore.setAutoStartOnBootEnabled(value)
    }

    fun setRequestBatteryOptimizationExclusion(value: Boolean) {
        settingsStore.setRequestBatteryOptimizationExclusion(value)
    }

    fun setParkingSpeedThresholdKmh(value: Float) {
        settingsStore.setParkingSpeedThresholdKmh(value)
    }

    fun setInstantTripSpeedThresholdKmh(value: Float) {
        settingsStore.setInstantTripSpeedThresholdKmh(value)
    }

    fun setRequiredMovingReadings(value: Int) {
        settingsStore.setRequiredMovingReadings(value)
    }

    fun setParkingTimeoutMinutes(value: Int) {
        settingsStore.setParkingTimeoutMinutes(value)
    }

    fun setMinTripAccuracyMeters(value: Int) {
        settingsStore.setMinTripAccuracyMeters(value)
    }

    fun setMinTripDetectionAccuracyMeters(value: Int) {
        settingsStore.setMinTripDetectionAccuracyMeters(value)
    }

    fun setActiveGpsIntervalSeconds(value: Int) {
        settingsStore.setActiveGpsIntervalSeconds(value)
    }

    fun setPassiveGpsIntervalSeconds(value: Int) {
        settingsStore.setPassiveGpsIntervalSeconds(value)
    }

    fun setBatteryActiveGpsIntervalSeconds(value: Int) {
        settingsStore.setBatteryActiveGpsIntervalSeconds(value)
    }

    fun setBatteryParkedGpsIntervalSeconds(value: Int) {
        settingsStore.setBatteryParkedGpsIntervalSeconds(value)
    }

    fun setDataRetentionDays(value: Int) {
        settingsStore.setDataRetentionDays(value)
    }

    suspend fun getTripPoints(tripId: Long): List<LocationPoint> {
        return db.locationPointDao().getPointsForTripSync(tripId)
    }
}

data class DayStats(
    val tripCount: Int = 0,
    val totalDistanceMeters: Double = 0.0
)
