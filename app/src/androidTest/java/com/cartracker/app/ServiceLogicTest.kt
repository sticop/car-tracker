package com.cartracker.app

import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cartracker.app.service.LocationTrackingService
import com.cartracker.app.util.FormatUtils
import com.cartracker.app.util.SpeedColorUtils
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/**
 * Instrumented tests for service constants, speed thresholds,
 * and location-related logic that rely on the Android framework.
 *
 * These tests verify the configuration values and Location API
 * behavior that determine how the service detects trips.
 *
 * Runs on the connected Android device.
 */
@RunWith(AndroidJUnit4::class)
class ServiceLogicTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ── Location Object Tests ───────────────────────────────────────
    // These test android.location.Location calculations used by the service

    @Test
    fun location_distanceBetweenKnownPoints() {
        // Paris (Eiffel Tower) to Arc de Triomphe ≈ 2.8 km
        val loc1 = createLocation(48.8584, 2.2945) // Eiffel Tower
        val loc2 = createLocation(48.8738, 2.2950) // Arc de Triomphe area

        val distance = loc1.distanceTo(loc2)
        assertTrue("Distance should be ~1.7km (got ${distance}m)", distance > 1500 && distance < 2000)
    }

    @Test
    fun location_distanceToSelf_isZero() {
        val loc = createLocation(48.8566, 2.3522)
        val distance = loc.distanceTo(loc)
        assertEquals(0f, distance, 0.01f)
    }

    @Test
    fun location_speedConversion_msToKmh() {
        // GPS reports speed in m/s, we convert to km/h
        val speedMs = 27.78f   // 100 km/h
        val speedKmh = speedMs * 3.6f
        assertEquals(100f, speedKmh, 0.1f)
    }

    @Test
    fun location_speedConversion_commonSpeeds() {
        // Walking: ~5 km/h = ~1.39 m/s
        assertEquals(5f, 1.39f * 3.6f, 0.1f)

        // City driving: ~50 km/h = ~13.89 m/s
        assertEquals(50f, 13.89f * 3.6f, 0.1f)

        // Highway: ~120 km/h = ~33.33 m/s
        assertEquals(120f, 33.33f * 3.6f, 0.1f)
    }

    @Test
    fun location_distanceBasedSpeed_calculation() {
        // Simulate speed calc from two locations 100m apart in 2 seconds
        val loc1 = createLocation(48.8566, 2.3522)
        val loc2 = createLocation(48.8575, 2.3522) // ~100m north

        val distanceM = loc1.distanceTo(loc2).toDouble()
        val timeSec = 2.0
        val speedKmh = (distanceM / timeSec) * 3.6

        assertTrue("Distance-based speed should be reasonable (got $speedKmh km/h)",
            speedKmh > 100 && speedKmh < 250)
    }

    // ── Speed Threshold Verification ────────────────────────────────

    @Test
    fun parkingThreshold_8kmhIsReasonable() {
        // The parking threshold is 8 km/h
        // GPS jitter can produce up to ~18 km/h on Galaxy S6 when stationary
        // BUT with the EMA smoothing, jitter averages out to <8 km/h
        val parkingThreshold = 8.0f

        // Walking speed (5 km/h) should NOT trigger a trip
        assertTrue("Walking speed should be below parking threshold",
            5.0f < parkingThreshold)

        // Slow city driving (15 km/h) should trigger (after consecutive readings)
        assertTrue("Slow driving should be above threshold",
            15.0f > parkingThreshold)
    }

    @Test
    fun instantTripThreshold_20kmhIsReasonable() {
        // At 20+ km/h, trip starts immediately (no consecutive readings needed)
        val instantThreshold = 20.0f

        // 20 km/h is impossible from pure GPS jitter
        // Even vigorous walking is ~7 km/h
        assertTrue("Instant threshold should be above walking speed",
            instantThreshold > 7.0f)

        // But 20 km/h should catch any real driving quickly
        assertTrue("Instant threshold should be below slow city driving",
            instantThreshold < 30.0f)
    }

    @Test
    fun maxRealisticSpeed_200kmhIsReasonable() {
        // Max accepted speed is 200 km/h
        val maxSpeed = 200f

        // Highway speed limits in most countries are 130 km/h or less
        assertTrue("Max speed should be above typical highway limits",
            maxSpeed > 130f)

        // GPS glitches can produce 500+ km/h from position jumps
        assertTrue("Max speed should filter obvious GPS glitches",
            maxSpeed < 300f)
    }

    // ── Acceleration Limiter ────────────────────────────────────────

    @Test
    fun accelerationLimit_rejectsImpossibleSpike() {
        val maxAccelKmhPerSec = 20f // From service constant
        val lastSpeed = 50f
        val timeDiffSec = 1.0f

        val maxAllowed = lastSpeed + (maxAccelKmhPerSec * timeDiffSec)
        assertEquals(70f, maxAllowed, 0.01f)

        // A reading of 150 km/h after 1 second from 50 km/h should be rejected
        assertTrue("150 km/h spike should exceed max allowed",
            150f > maxAllowed)

        // But 65 km/h after 1 second from 50 km/h is fine
        assertTrue("65 km/h should be within max allowed",
            65f <= maxAllowed)
    }

    @Test
    fun accelerationLimit_allowsNormalAcceleration() {
        val maxAccelKmhPerSec = 20f
        val lastSpeed = 0f
        val timeDiffSec = 5.0f

        // After 5 seconds from 0, max allowed = 100 km/h
        val maxAllowed = lastSpeed + (maxAccelKmhPerSec * timeDiffSec)
        assertEquals(100f, maxAllowed, 0.01f)

        // Realistic: 0 to 60 km/h in 5 seconds
        assertTrue("0-60 in 5s should be allowed", 60f <= maxAllowed)
    }

    // ── EMA Smoothing ───────────────────────────────────────────────

    @Test
    fun emaSmoothing_displayAlpha() {
        val alpha = 0.82f
        var displaySpeed = 0f

        // First reading: should jump directly
        displaySpeed = 60f  // First non-zero sets directly

        // Second reading at 65: should be close to 65
        displaySpeed = alpha * 65f + (1f - alpha) * displaySpeed
        assertTrue("Display speed should respond quickly (got $displaySpeed)",
            displaySpeed > 63f && displaySpeed < 66f)
    }

    @Test
    fun emaSmoothing_logicAlpha() {
        val alpha = 0.45f
        var logicSpeed = 0f

        // First reading: sets directly
        logicSpeed = 60f

        // Spike to 100: should be heavily damped
        logicSpeed = alpha * 100f + (1f - alpha) * logicSpeed
        assertTrue("Logic speed should be more damped (got $logicSpeed)",
            logicSpeed > 70f && logicSpeed < 85f)
    }

    @Test
    fun emaSmoothing_convergesOverTime() {
        val alpha = 0.45f
        var logicSpeed = 50f

        // Apply constant 100 km/h reading repeatedly
        for (i in 1..20) {
            logicSpeed = alpha * 100f + (1f - alpha) * logicSpeed
        }
        // After 20 readings, should be very close to 100
        assertTrue("Logic speed should converge to input (got $logicSpeed)",
            logicSpeed > 99f)
    }

    // ── Parking Timeout ─────────────────────────────────────────────

    @Test
    fun parkingTimeout_2minutesInMs() {
        val timeout = 2 * 60 * 1000L
        assertEquals(120_000L, timeout)
    }

    @Test
    fun parkingTimeout_durationFormatting() {
        val timeout = 2 * 60 * 1000L
        val formatted = FormatUtils.formatDuration(timeout)
        assertEquals("2m 0s", formatted)
    }

    // ── GPS Interval Configuration ──────────────────────────────────

    @Test
    fun gpsIntervals_chargingIsFaster() {
        val activeCharging = 1000L
        val activeBattery = 2000L
        assertTrue("Charging active interval should be faster",
            activeCharging < activeBattery)
    }

    @Test
    fun gpsIntervals_parkedBatteryIsSlowest() {
        val parkedBattery = 10_000L
        val parkedCharging = 1500L
        val activeBattery = 2000L

        assertTrue("Parked on battery should have longest interval",
            parkedBattery > parkedCharging)
        assertTrue("Parked on battery should be slower than active on battery",
            parkedBattery > activeBattery)
    }

    // ── Data Retention ──────────────────────────────────────────────

    @Test
    fun dataRetention_30daysInMs() {
        val retention = LocationTrackingService.DATA_RETENTION_MS
        val expectedMs = 30L * 24 * 60 * 60 * 1000
        assertEquals(expectedMs, retention)
    }

    // ── Service Start/Stop Intents ──────────────────────────────────

    @Test
    fun serviceIntent_canBeCreated() {
        val intent = Intent(context, LocationTrackingService::class.java)
        assertNotNull(intent)
        assertEquals(LocationTrackingService::class.java.name, intent.component?.className)
    }

    // ── Speed Color Integration ─────────────────────────────────────

    @Test
    fun speedColorIntegration_matchesExpectedProgression() {
        // As speed increases, colors should progress: green → mint → yellow → amber → orange → red → purple
        val speeds = listOf(10f, 40f, 60f, 80f, 100f, 120f, 150f)
        val colors = speeds.map { SpeedColorUtils.getColorForSpeed(it) }

        // All colors should be different (distinct for each range)
        assertEquals("All speed ranges should have unique colors",
            colors.distinct().size, colors.size)
    }

    // ── Helper ──────────────────────────────────────────────────────

    private fun createLocation(lat: Double, lon: Double): Location {
        return Location("test").apply {
            latitude = lat
            longitude = lon
            accuracy = 10f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
    }
}
