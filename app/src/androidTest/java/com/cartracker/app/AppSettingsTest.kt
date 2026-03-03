package com.cartracker.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cartracker.app.settings.AppSettings
import com.cartracker.app.settings.AppSettingsStore
import com.cartracker.app.settings.MapStylePreference
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/**
 * Instrumented tests for AppSettings defaults, AppSettingsStore persistence,
 * clamping / coercion logic, and cross-field constraints.
 *
 * Runs on the connected Android device.
 */
@RunWith(AndroidJUnit4::class)
class AppSettingsTest {

    private lateinit var context: Context
    private lateinit var store: AppSettingsStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear any stale prefs so each test starts clean
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = AppSettingsStore(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // ── AppSettings data class defaults ─────────────────────────────

    @Test
    fun defaults_mapStyleIsDetail() {
        val d = AppSettings()
        assertEquals(MapStylePreference.DETAIL, d.defaultMapStyle)
    }

    @Test
    fun defaults_autoCenterIsTrue() {
        assertTrue(AppSettings().autoCenterMap)
    }

    @Test
    fun defaults_offlineCityPromptEnabled() {
        assertTrue(AppSettings().offlineCityPromptEnabled)
    }

    @Test
    fun defaults_autoTileCacheEnabled() {
        assertTrue(AppSettings().autoTileCacheEnabled)
    }

    @Test
    fun defaults_webSyncEnabled() {
        assertTrue(AppSettings().webSyncEnabled)
    }

    @Test
    fun defaults_batterySaverModeEnabled() {
        assertTrue(AppSettings().batterySaverModeEnabled)
    }

    @Test
    fun defaults_autoStartOnBootEnabled() {
        assertTrue(AppSettings().autoStartOnBootEnabled)
    }

    @Test
    fun defaults_requestBatteryOptExclusion() {
        assertTrue(AppSettings().requestBatteryOptimizationExclusion)
    }

    @Test
    fun defaults_numericValues() {
        val d = AppSettings()
        assertEquals(8f, d.parkingSpeedThresholdKmh, 0.01f)
        assertEquals(20f, d.instantTripSpeedThresholdKmh, 0.01f)
        assertEquals(3, d.requiredMovingReadings)
        assertEquals(2, d.parkingTimeoutMinutes)
        assertEquals(30, d.minTripAccuracyMeters)
        assertEquals(50, d.minTripDetectionAccuracyMeters)
        assertEquals(1, d.activeGpsIntervalSeconds)
        assertEquals(2, d.passiveGpsIntervalSeconds)
        assertEquals(2, d.batteryActiveGpsIntervalSeconds)
        assertEquals(10, d.batteryParkedGpsIntervalSeconds)
        assertEquals(30, d.dataRetentionDays)
    }

    // ── Store reads defaults when prefs are empty ───────────────────

    @Test
    fun store_initialSettingsMatchDefaults() {
        val s = store.settings.value
        assertEquals(MapStylePreference.DETAIL, s.defaultMapStyle)
        assertTrue(s.autoCenterMap)
        assertTrue(s.offlineCityPromptEnabled)
        assertTrue(s.autoTileCacheEnabled)
        assertTrue(s.webSyncEnabled)
        assertTrue(s.batterySaverModeEnabled)
        assertTrue(s.autoStartOnBootEnabled)
        assertTrue(s.requestBatteryOptimizationExclusion)
        assertEquals(8f, s.parkingSpeedThresholdKmh, 0.01f)
        assertEquals(20f, s.instantTripSpeedThresholdKmh, 0.01f)
        assertEquals(3, s.requiredMovingReadings)
        assertEquals(2, s.parkingTimeoutMinutes)
        assertEquals(30, s.minTripAccuracyMeters)
        assertEquals(50, s.minTripDetectionAccuracyMeters)
        assertEquals(1, s.activeGpsIntervalSeconds)
        assertEquals(2, s.passiveGpsIntervalSeconds)
        assertEquals(2, s.batteryActiveGpsIntervalSeconds)
        assertEquals(10, s.batteryParkedGpsIntervalSeconds)
        assertEquals(30, s.dataRetentionDays)
    }

    // ── Boolean toggle setters ──────────────────────────────────────

    @Test
    fun store_setAutoCenterMap() {
        store.setAutoCenterMap(false)
        assertFalse(store.settings.value.autoCenterMap)
        store.setAutoCenterMap(true)
        assertTrue(store.settings.value.autoCenterMap)
    }

    @Test
    fun store_setOfflineCityPromptEnabled() {
        store.setOfflineCityPromptEnabled(false)
        assertFalse(store.settings.value.offlineCityPromptEnabled)
    }

    @Test
    fun store_setAutoTileCacheEnabled() {
        store.setAutoTileCacheEnabled(false)
        assertFalse(store.settings.value.autoTileCacheEnabled)
    }

    @Test
    fun store_setWebSyncEnabled() {
        store.setWebSyncEnabled(false)
        assertFalse(store.settings.value.webSyncEnabled)
    }

    @Test
    fun store_setBatterySaverModeEnabled() {
        store.setBatterySaverModeEnabled(false)
        assertFalse(store.settings.value.batterySaverModeEnabled)
    }

    @Test
    fun store_setAutoStartOnBootEnabled() {
        store.setAutoStartOnBootEnabled(false)
        assertFalse(store.settings.value.autoStartOnBootEnabled)
    }

    @Test
    fun store_setRequestBatteryOptExclusion() {
        store.setRequestBatteryOptimizationExclusion(false)
        assertFalse(store.settings.value.requestBatteryOptimizationExclusion)
    }

    // ── Map style setter ────────────────────────────────────────────

    @Test
    fun store_setDefaultMapStyle_dark() {
        store.setDefaultMapStyle(MapStylePreference.DARK)
        assertEquals(MapStylePreference.DARK, store.settings.value.defaultMapStyle)
    }

    @Test
    fun store_setDefaultMapStyle_detail() {
        store.setDefaultMapStyle(MapStylePreference.DARK)
        store.setDefaultMapStyle(MapStylePreference.DETAIL)
        assertEquals(MapStylePreference.DETAIL, store.settings.value.defaultMapStyle)
    }

    // ── Numeric setters with clamping ───────────────────────────────

    @Test
    fun store_parkingSpeedThreshold_clampsLow() {
        store.setParkingSpeedThresholdKmh(0f)
        assertEquals(3f, store.settings.value.parkingSpeedThresholdKmh, 0.01f)
    }

    @Test
    fun store_parkingSpeedThreshold_clampsHigh() {
        store.setParkingSpeedThresholdKmh(999f)
        assertEquals(30f, store.settings.value.parkingSpeedThresholdKmh, 0.01f)
    }

    @Test
    fun store_parkingSpeedThreshold_normalValue() {
        store.setParkingSpeedThresholdKmh(15f)
        assertEquals(15f, store.settings.value.parkingSpeedThresholdKmh, 0.01f)
    }

    @Test
    fun store_instantTripSpeedThreshold_clampsLow() {
        store.setInstantTripSpeedThresholdKmh(1f)
        assertEquals(10f, store.settings.value.instantTripSpeedThresholdKmh, 0.01f)
    }

    @Test
    fun store_instantTripSpeedThreshold_clampsHigh() {
        store.setInstantTripSpeedThresholdKmh(200f)
        assertEquals(80f, store.settings.value.instantTripSpeedThresholdKmh, 0.01f)
    }

    @Test
    fun store_instantTripSpeed_staysAboveParkingSpeed() {
        // Set parking speed to 25, instant trip to 20; instant must be > parking
        store.setParkingSpeedThresholdKmh(25f)
        store.setInstantTripSpeedThresholdKmh(20f)
        val s = store.settings.value
        assertTrue("Instant trip speed must exceed parking speed",
            s.instantTripSpeedThresholdKmh > s.parkingSpeedThresholdKmh)
    }

    @Test
    fun store_requiredMovingReadings_clampsLow() {
        store.setRequiredMovingReadings(0)
        assertEquals(1, store.settings.value.requiredMovingReadings)
    }

    @Test
    fun store_requiredMovingReadings_clampsHigh() {
        store.setRequiredMovingReadings(999)
        assertEquals(10, store.settings.value.requiredMovingReadings)
    }

    @Test
    fun store_parkingTimeoutMinutes_clampsLow() {
        store.setParkingTimeoutMinutes(0)
        assertEquals(1, store.settings.value.parkingTimeoutMinutes)
    }

    @Test
    fun store_parkingTimeoutMinutes_clampsHigh() {
        store.setParkingTimeoutMinutes(999)
        assertEquals(30, store.settings.value.parkingTimeoutMinutes)
    }

    @Test
    fun store_parkingTimeoutMinutes_normalValue() {
        store.setParkingTimeoutMinutes(5)
        assertEquals(5, store.settings.value.parkingTimeoutMinutes)
    }

    @Test
    fun store_minTripAccuracyMeters_clampsLow() {
        store.setMinTripAccuracyMeters(0)
        assertEquals(5, store.settings.value.minTripAccuracyMeters)
    }

    @Test
    fun store_minTripAccuracyMeters_clampsHigh() {
        store.setMinTripAccuracyMeters(500)
        assertEquals(100, store.settings.value.minTripAccuracyMeters)
    }

    @Test
    fun store_minTripDetectionAccuracy_clampsLow() {
        // Setting to 0 clamps to 10, but cross-field constraint
        // max(detection, tripAccuracy) applies — default tripAccuracy = 30
        store.setMinTripDetectionAccuracyMeters(0)
        val s = store.settings.value
        assertTrue("Detection accuracy must be >= 10 (raw clamp) and >= tripAccuracy",
            s.minTripDetectionAccuracyMeters >= 10 &&
            s.minTripDetectionAccuracyMeters >= s.minTripAccuracyMeters)
    }

    @Test
    fun store_minTripDetectionAccuracy_clampsHigh() {
        store.setMinTripDetectionAccuracyMeters(999)
        assertEquals(150, store.settings.value.minTripDetectionAccuracyMeters)
    }

    @Test
    fun store_minDetectionAccuracy_staysAboveTripAccuracy() {
        // Set trip accuracy to 80, detection to 50; detection must be >= trip accuracy
        store.setMinTripAccuracyMeters(80)
        store.setMinTripDetectionAccuracyMeters(50)
        val s = store.settings.value
        assertTrue("Detection accuracy must be >= trip accuracy",
            s.minTripDetectionAccuracyMeters >= s.minTripAccuracyMeters)
    }

    @Test
    fun store_activeGpsIntervalSeconds_clampsLow() {
        store.setActiveGpsIntervalSeconds(0)
        assertEquals(1, store.settings.value.activeGpsIntervalSeconds)
    }

    @Test
    fun store_activeGpsIntervalSeconds_clampsHigh() {
        store.setActiveGpsIntervalSeconds(999)
        assertEquals(10, store.settings.value.activeGpsIntervalSeconds)
    }

    @Test
    fun store_passiveGpsIntervalSeconds_clampsLow() {
        store.setPassiveGpsIntervalSeconds(0)
        assertEquals(1, store.settings.value.passiveGpsIntervalSeconds)
    }

    @Test
    fun store_passiveGpsIntervalSeconds_clampsHigh() {
        store.setPassiveGpsIntervalSeconds(999)
        assertEquals(30, store.settings.value.passiveGpsIntervalSeconds)
    }

    @Test
    fun store_batteryActiveGpsIntervalSeconds_clampsLow() {
        store.setBatteryActiveGpsIntervalSeconds(0)
        assertEquals(1, store.settings.value.batteryActiveGpsIntervalSeconds)
    }

    @Test
    fun store_batteryActiveGpsIntervalSeconds_clampsHigh() {
        store.setBatteryActiveGpsIntervalSeconds(999)
        assertEquals(30, store.settings.value.batteryActiveGpsIntervalSeconds)
    }

    @Test
    fun store_batteryParkedGpsIntervalSeconds_clampsLow() {
        store.setBatteryParkedGpsIntervalSeconds(0)
        assertEquals(3, store.settings.value.batteryParkedGpsIntervalSeconds)
    }

    @Test
    fun store_batteryParkedGpsIntervalSeconds_clampsHigh() {
        store.setBatteryParkedGpsIntervalSeconds(999)
        assertEquals(180, store.settings.value.batteryParkedGpsIntervalSeconds)
    }

    @Test
    fun store_dataRetentionDays_clampsLow() {
        store.setDataRetentionDays(0)
        assertEquals(1, store.settings.value.dataRetentionDays)
    }

    @Test
    fun store_dataRetentionDays_clampsHigh() {
        store.setDataRetentionDays(999)
        assertEquals(365, store.settings.value.dataRetentionDays)
    }

    @Test
    fun store_dataRetentionDays_normalValue() {
        store.setDataRetentionDays(90)
        assertEquals(90, store.settings.value.dataRetentionDays)
    }

    // ── Persistence across new store instances ──────────────────────

    @Test
    fun store_settingsPersistAcrossInstances() {
        store.setDefaultMapStyle(MapStylePreference.DARK)
        store.setAutoCenterMap(false)
        store.setParkingTimeoutMinutes(10)
        store.setDataRetentionDays(60)
        store.setBatterySaverModeEnabled(false)

        // Create a new store reading the same SharedPreferences
        val store2 = AppSettingsStore(context)
        val s = store2.settings.value

        assertEquals(MapStylePreference.DARK, s.defaultMapStyle)
        assertFalse(s.autoCenterMap)
        assertEquals(10, s.parkingTimeoutMinutes)
        assertEquals(60, s.dataRetentionDays)
        assertFalse(s.batterySaverModeEnabled)
    }

    // ── StateFlow reactivity ────────────────────────────────────────

    @Test
    fun store_stateFlowUpdatesOnSet() {
        val initial = store.settings.value.parkingTimeoutMinutes
        assertEquals(2, initial)
        store.setParkingTimeoutMinutes(15)
        assertEquals(15, store.settings.value.parkingTimeoutMinutes)
    }

    @Test
    fun store_multipleRapidUpdates() {
        for (i in 1..10) {
            store.setParkingTimeoutMinutes(i)
        }
        assertEquals(10, store.settings.value.parkingTimeoutMinutes)
    }

    // ── Companion helpers ───────────────────────────────────────────

    @Test
    fun companion_shouldAutoStartOnBoot_defaultTrue() {
        assertTrue(AppSettingsStore.shouldAutoStartOnBoot(context))
    }

    @Test
    fun companion_shouldAutoStartOnBoot_afterDisable() {
        store.setAutoStartOnBootEnabled(false)
        assertFalse(AppSettingsStore.shouldAutoStartOnBoot(context))
    }

    @Test
    fun companion_shouldRequestBatteryOptExclusion_defaultTrue() {
        assertTrue(AppSettingsStore.shouldRequestBatteryOptimizationExclusion(context))
    }

    @Test
    fun companion_shouldRequestBatteryOptExclusion_afterDisable() {
        store.setRequestBatteryOptimizationExclusion(false)
        assertFalse(AppSettingsStore.shouldRequestBatteryOptimizationExclusion(context))
    }

    // ── MapStylePreference enum ─────────────────────────────────────

    @Test
    fun mapStylePreference_hasExpectedValues() {
        val values = MapStylePreference.values()
        assertEquals(2, values.size)
        assertTrue(values.contains(MapStylePreference.DETAIL))
        assertTrue(values.contains(MapStylePreference.DARK))
    }

    @Test
    fun mapStylePreference_valueOf() {
        assertEquals(MapStylePreference.DETAIL, MapStylePreference.valueOf("DETAIL"))
        assertEquals(MapStylePreference.DARK, MapStylePreference.valueOf("DARK"))
    }

    // ── Edge cases: invalid enum fallback ───────────────────────────

    @Test
    fun store_corruptMapStyleFallsBackToDetail() {
        // Manually write an invalid value to prefs
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().putString("default_map_style", "NONEXISTENT").commit()
        val fresh = AppSettingsStore(context)
        assertEquals(MapStylePreference.DETAIL, fresh.settings.value.defaultMapStyle)
    }
}
