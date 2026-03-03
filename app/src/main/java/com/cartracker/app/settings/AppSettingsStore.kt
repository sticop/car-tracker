package com.cartracker.app.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

enum class MapStylePreference {
    DETAIL,
    DARK
}

data class AppSettings(
    val defaultMapStyle: MapStylePreference = MapStylePreference.DETAIL,
    val autoCenterMap: Boolean = true,
    val offlineCityPromptEnabled: Boolean = true,
    val autoTileCacheEnabled: Boolean = true,
    val webSyncEnabled: Boolean = true,
    val batterySaverModeEnabled: Boolean = true,
    val autoStartOnBootEnabled: Boolean = true,
    val requestBatteryOptimizationExclusion: Boolean = true,
    val parkingSpeedThresholdKmh: Float = 8f,
    val instantTripSpeedThresholdKmh: Float = 20f,
    val requiredMovingReadings: Int = 3,
    val parkingTimeoutMinutes: Int = 2,
    val minTripAccuracyMeters: Int = 30,
    val minTripDetectionAccuracyMeters: Int = 50,
    val activeGpsIntervalSeconds: Int = 1,
    val passiveGpsIntervalSeconds: Int = 2,
    val batteryActiveGpsIntervalSeconds: Int = 2,
    val batteryParkedGpsIntervalSeconds: Int = 10,
    val dataRetentionDays: Int = 30
)

class AppSettingsStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun setDefaultMapStyle(value: MapStylePreference) {
        update { putString(KEY_DEFAULT_MAP_STYLE, value.name) }
    }

    fun setAutoCenterMap(value: Boolean) {
        update { putBoolean(KEY_AUTO_CENTER_MAP, value) }
    }

    fun setOfflineCityPromptEnabled(value: Boolean) {
        update { putBoolean(KEY_OFFLINE_CITY_PROMPT, value) }
    }

    fun setAutoTileCacheEnabled(value: Boolean) {
        update { putBoolean(KEY_AUTO_TILE_CACHE, value) }
    }

    fun setWebSyncEnabled(value: Boolean) {
        update { putBoolean(KEY_WEB_SYNC_ENABLED, value) }
    }

    fun setBatterySaverModeEnabled(value: Boolean) {
        update { putBoolean(KEY_BATTERY_SAVER_MODE, value) }
    }

    fun setAutoStartOnBootEnabled(value: Boolean) {
        update { putBoolean(KEY_AUTO_START_ON_BOOT, value) }
    }

    fun setRequestBatteryOptimizationExclusion(value: Boolean) {
        update { putBoolean(KEY_BATTERY_OPT_EXCLUSION, value) }
    }

    fun setParkingSpeedThresholdKmh(value: Float) {
        update { putFloat(KEY_PARKING_SPEED_THRESHOLD, value.coerceIn(3f, 30f)) }
    }

    fun setInstantTripSpeedThresholdKmh(value: Float) {
        update { putFloat(KEY_INSTANT_TRIP_SPEED_THRESHOLD, value.coerceIn(10f, 80f)) }
    }

    fun setRequiredMovingReadings(value: Int) {
        update { putInt(KEY_REQUIRED_MOVING_READINGS, value.coerceIn(1, 10)) }
    }

    fun setParkingTimeoutMinutes(value: Int) {
        update { putInt(KEY_PARKING_TIMEOUT_MINUTES, value.coerceIn(1, 30)) }
    }

    fun setMinTripAccuracyMeters(value: Int) {
        update { putInt(KEY_MIN_TRIP_ACCURACY_METERS, value.coerceIn(5, 100)) }
    }

    fun setMinTripDetectionAccuracyMeters(value: Int) {
        update { putInt(KEY_MIN_TRIP_DETECTION_ACCURACY_METERS, value.coerceIn(10, 150)) }
    }

    fun setActiveGpsIntervalSeconds(value: Int) {
        update { putInt(KEY_ACTIVE_GPS_INTERVAL_SECONDS, value.coerceIn(1, 10)) }
    }

    fun setPassiveGpsIntervalSeconds(value: Int) {
        update { putInt(KEY_PASSIVE_GPS_INTERVAL_SECONDS, value.coerceIn(1, 30)) }
    }

    fun setBatteryActiveGpsIntervalSeconds(value: Int) {
        update { putInt(KEY_BATTERY_ACTIVE_GPS_INTERVAL_SECONDS, value.coerceIn(1, 30)) }
    }

    fun setBatteryParkedGpsIntervalSeconds(value: Int) {
        update { putInt(KEY_BATTERY_PARKED_GPS_INTERVAL_SECONDS, value.coerceIn(3, 180)) }
    }

    fun setDataRetentionDays(value: Int) {
        update { putInt(KEY_DATA_RETENTION_DAYS, value.coerceIn(1, 365)) }
    }

    private fun update(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _settings.value = readSettings()
    }

    private fun readSettings(): AppSettings {
        val parkingSpeed = prefs.getFloat(KEY_PARKING_SPEED_THRESHOLD, 8f).coerceIn(3f, 30f)
        val instantSpeedRaw = prefs.getFloat(KEY_INSTANT_TRIP_SPEED_THRESHOLD, 20f).coerceIn(10f, 80f)
        val instantSpeed = max(instantSpeedRaw, parkingSpeed + 1f)

        val minTripAccuracy = prefs.getInt(KEY_MIN_TRIP_ACCURACY_METERS, 30).coerceIn(5, 100)
        val minDetectionAccuracyRaw = prefs.getInt(KEY_MIN_TRIP_DETECTION_ACCURACY_METERS, 50).coerceIn(10, 150)
        val minDetectionAccuracy = max(minDetectionAccuracyRaw, minTripAccuracy)

        return AppSettings(
            defaultMapStyle = runCatching {
                MapStylePreference.valueOf(
                    prefs.getString(KEY_DEFAULT_MAP_STYLE, MapStylePreference.DETAIL.name)
                        ?: MapStylePreference.DETAIL.name
                )
            }.getOrElse { MapStylePreference.DETAIL },
            autoCenterMap = prefs.getBoolean(KEY_AUTO_CENTER_MAP, true),
            offlineCityPromptEnabled = prefs.getBoolean(KEY_OFFLINE_CITY_PROMPT, true),
            autoTileCacheEnabled = prefs.getBoolean(KEY_AUTO_TILE_CACHE, true),
            webSyncEnabled = prefs.getBoolean(KEY_WEB_SYNC_ENABLED, true),
            batterySaverModeEnabled = prefs.getBoolean(KEY_BATTERY_SAVER_MODE, true),
            autoStartOnBootEnabled = prefs.getBoolean(KEY_AUTO_START_ON_BOOT, true),
            requestBatteryOptimizationExclusion = prefs.getBoolean(KEY_BATTERY_OPT_EXCLUSION, true),
            parkingSpeedThresholdKmh = parkingSpeed,
            instantTripSpeedThresholdKmh = instantSpeed,
            requiredMovingReadings = prefs.getInt(KEY_REQUIRED_MOVING_READINGS, 3).coerceIn(1, 10),
            parkingTimeoutMinutes = prefs.getInt(KEY_PARKING_TIMEOUT_MINUTES, 2).coerceIn(1, 30),
            minTripAccuracyMeters = minTripAccuracy,
            minTripDetectionAccuracyMeters = minDetectionAccuracy,
            activeGpsIntervalSeconds = prefs.getInt(KEY_ACTIVE_GPS_INTERVAL_SECONDS, 1).coerceIn(1, 10),
            passiveGpsIntervalSeconds = prefs.getInt(KEY_PASSIVE_GPS_INTERVAL_SECONDS, 2).coerceIn(1, 30),
            batteryActiveGpsIntervalSeconds = prefs.getInt(KEY_BATTERY_ACTIVE_GPS_INTERVAL_SECONDS, 2).coerceIn(1, 30),
            batteryParkedGpsIntervalSeconds = prefs.getInt(KEY_BATTERY_PARKED_GPS_INTERVAL_SECONDS, 10).coerceIn(3, 180),
            dataRetentionDays = prefs.getInt(KEY_DATA_RETENTION_DAYS, 30).coerceIn(1, 365)
        )
    }

    companion object {
        private const val PREFS_NAME = "app_settings"

        private const val KEY_DEFAULT_MAP_STYLE = "default_map_style"
        private const val KEY_AUTO_CENTER_MAP = "auto_center_map"
        private const val KEY_OFFLINE_CITY_PROMPT = "offline_city_prompt"
        private const val KEY_AUTO_TILE_CACHE = "auto_tile_cache"
        private const val KEY_WEB_SYNC_ENABLED = "web_sync_enabled"
        private const val KEY_BATTERY_SAVER_MODE = "battery_saver_mode"
        private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
        private const val KEY_BATTERY_OPT_EXCLUSION = "battery_opt_exclusion"
        private const val KEY_PARKING_SPEED_THRESHOLD = "parking_speed_threshold"
        private const val KEY_INSTANT_TRIP_SPEED_THRESHOLD = "instant_trip_speed_threshold"
        private const val KEY_REQUIRED_MOVING_READINGS = "required_moving_readings"
        private const val KEY_PARKING_TIMEOUT_MINUTES = "parking_timeout_minutes"
        private const val KEY_MIN_TRIP_ACCURACY_METERS = "min_trip_accuracy_meters"
        private const val KEY_MIN_TRIP_DETECTION_ACCURACY_METERS = "min_trip_detection_accuracy_meters"
        private const val KEY_ACTIVE_GPS_INTERVAL_SECONDS = "active_gps_interval_seconds"
        private const val KEY_PASSIVE_GPS_INTERVAL_SECONDS = "passive_gps_interval_seconds"
        private const val KEY_BATTERY_ACTIVE_GPS_INTERVAL_SECONDS = "battery_active_gps_interval_seconds"
        private const val KEY_BATTERY_PARKED_GPS_INTERVAL_SECONDS = "battery_parked_gps_interval_seconds"
        private const val KEY_DATA_RETENTION_DAYS = "data_retention_days"

        fun shouldAutoStartOnBoot(context: Context): Boolean {
            return context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_START_ON_BOOT, true)
        }

        fun shouldRequestBatteryOptimizationExclusion(context: Context): Boolean {
            return context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_BATTERY_OPT_EXCLUSION, true)
        }
    }
}
