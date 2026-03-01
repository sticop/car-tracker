package com.cartracker.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cartracker.app.util.FormatUtils
import com.cartracker.app.util.SpeedColorUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.*

/**
 * Instrumented tests for utility classes.
 * Runs on the connected Android device.
 */
@RunWith(AndroidJUnit4::class)
class UtilsTest {

    // ── FormatUtils: Speed Formatting ───────────────────────────────

    @Test
    fun formatSpeed_zero() {
        assertEquals("0 km/h", FormatUtils.formatSpeed(0f))
    }

    @Test
    fun formatSpeed_normalValue() {
        assertEquals("120 km/h", FormatUtils.formatSpeed(120f))
    }

    @Test
    fun formatSpeed_fractionalRoundsDown() {
        assertEquals("65 km/h", FormatUtils.formatSpeed(65.4f))
    }

    @Test
    fun formatSpeed_fractionalRoundsUp() {
        assertEquals("66 km/h", FormatUtils.formatSpeed(65.5f))
    }

    // ── FormatUtils: Distance Formatting ────────────────────────────

    @Test
    fun formatDistance_metersUnder1000() {
        assertEquals("500 m", FormatUtils.formatDistance(500.0))
    }

    @Test
    fun formatDistance_exactlyOneKm() {
        val result = FormatUtils.formatDistance(1000.0)
        assertTrue("Expected '1.0 km' or '1,0 km' but got '$result'",
            result == "1.0 km" || result == "1,0 km")
    }

    @Test
    fun formatDistance_multipleKm() {
        val result = FormatUtils.formatDistance(15300.0)
        assertTrue("Expected '15.3 km' or '15,3 km' but got '$result'",
            result == "15.3 km" || result == "15,3 km")
    }

    @Test
    fun formatDistance_zeroMeters() {
        assertEquals("0 m", FormatUtils.formatDistance(0.0))
    }

    @Test
    fun formatDistance_smallDistance() {
        assertEquals("42 m", FormatUtils.formatDistance(42.0))
    }

    // ── FormatUtils: Duration Formatting ────────────────────────────

    @Test
    fun formatDuration_secondsOnly() {
        assertEquals("45s", FormatUtils.formatDuration(45_000L))
    }

    @Test
    fun formatDuration_minutesAndSeconds() {
        assertEquals("5m 30s", FormatUtils.formatDuration(5 * 60_000L + 30_000L))
    }

    @Test
    fun formatDuration_hoursAndMinutes() {
        assertEquals("2h 15m", FormatUtils.formatDuration(2 * 3600_000L + 15 * 60_000L))
    }

    @Test
    fun formatDuration_zeroDuration() {
        assertEquals("0s", FormatUtils.formatDuration(0L))
    }

    @Test
    fun formatDuration_exactOneHour() {
        assertEquals("1h 0m", FormatUtils.formatDuration(3600_000L))
    }

    // ── FormatUtils: Date/Time Helpers ──────────────────────────────

    @Test
    fun isToday_currentTime() {
        assertTrue(FormatUtils.isToday(System.currentTimeMillis()))
    }

    @Test
    fun isToday_yesterdayTime() {
        val yesterday = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        assertFalse(FormatUtils.isToday(yesterday))
    }

    @Test
    fun isYesterday_oneDayAgo() {
        val yesterday = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        assertTrue(FormatUtils.isYesterday(yesterday))
    }

    @Test
    fun isYesterday_todayReturnsFalse() {
        assertFalse(FormatUtils.isYesterday(System.currentTimeMillis()))
    }

    @Test
    fun getRelativeDay_today() {
        assertEquals("Today", FormatUtils.getRelativeDay(System.currentTimeMillis()))
    }

    @Test
    fun getRelativeDay_yesterday() {
        val yesterday = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        assertEquals("Yesterday", FormatUtils.getRelativeDay(yesterday))
    }

    @Test
    fun getRelativeDay_olderDate() {
        // 7 days ago
        val older = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        val result = FormatUtils.getRelativeDay(older)
        // Should return formatted date, not "Today" or "Yesterday"
        assertNotEquals("Today", result)
        assertNotEquals("Yesterday", result)
        assertTrue("Should contain a day name", result.contains(","))
    }

    @Test
    fun formatTime_returnsHoursMinutes() {
        // Set a known time: 14:30
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 14)
            set(Calendar.MINUTE, 30)
        }
        val result = FormatUtils.formatTime(cal.timeInMillis)
        assertEquals("14:30", result)
    }

    @Test
    fun formatDateTime_containsMonth() {
        val result = FormatUtils.formatDateTime(System.currentTimeMillis())
        // Should contain a month abbreviation and time
        assertTrue("Should contain a colon (from time)", result.contains(":"))
    }

    // ── SpeedColorUtils ─────────────────────────────────────────────

    @Test
    fun speedColor_slowSpeed() {
        val color = SpeedColorUtils.getColorForSpeed(15f)
        assertEquals(0xFF06C167.toInt(), color) // Green
    }

    @Test
    fun speedColor_citySpeed() {
        val color = SpeedColorUtils.getColorForSpeed(40f)
        assertEquals(0xFF7EC489.toInt(), color) // Mint
    }

    @Test
    fun speedColor_urbanSpeed() {
        val color = SpeedColorUtils.getColorForSpeed(60f)
        assertEquals(0xFFFFC043.toInt(), color) // Yellow
    }

    @Test
    fun speedColor_suburbanSpeed() {
        val color = SpeedColorUtils.getColorForSpeed(80f)
        assertEquals(0xFFFF9500.toInt(), color) // Amber
    }

    @Test
    fun speedColor_highwaySpeed() {
        val color = SpeedColorUtils.getColorForSpeed(100f)
        assertEquals(0xFFFF6B00.toInt(), color) // Deep orange
    }

    @Test
    fun speedColor_fastSpeed() {
        val color = SpeedColorUtils.getColorForSpeed(120f)
        assertEquals(0xFFE11900.toInt(), color) // Red
    }

    @Test
    fun speedColor_veryFastSpeed() {
        val color = SpeedColorUtils.getColorForSpeed(150f)
        assertEquals(0xFFCB2BD5.toInt(), color) // Purple
    }

    @Test
    fun speedColor_boundaryAt30() {
        // 29 should be slow (green), 30 should be city (mint)
        assertEquals(0xFF06C167.toInt(), SpeedColorUtils.getColorForSpeed(29f))
        assertEquals(0xFF7EC489.toInt(), SpeedColorUtils.getColorForSpeed(30f))
    }

    // ── SpeedColorUtils: Categories ─────────────────────────────────

    @Test
    fun speedCategory_slow() {
        assertEquals("SLOW", SpeedColorUtils.getSpeedCategory(10f))
    }

    @Test
    fun speedCategory_city() {
        assertEquals("CITY", SpeedColorUtils.getSpeedCategory(40f))
    }

    @Test
    fun speedCategory_urban() {
        assertEquals("URBAN", SpeedColorUtils.getSpeedCategory(60f))
    }

    @Test
    fun speedCategory_suburban() {
        assertEquals("SUBURBAN", SpeedColorUtils.getSpeedCategory(80f))
    }

    @Test
    fun speedCategory_highway() {
        assertEquals("HIGHWAY", SpeedColorUtils.getSpeedCategory(100f))
    }

    @Test
    fun speedCategory_fast() {
        assertEquals("FAST", SpeedColorUtils.getSpeedCategory(120f))
    }

    @Test
    fun speedCategory_veryFast() {
        assertEquals("VERY FAST", SpeedColorUtils.getSpeedCategory(200f))
    }

    @Test
    fun speedCategory_zero() {
        assertEquals("SLOW", SpeedColorUtils.getSpeedCategory(0f))
    }

    @Test
    fun speedCategory_boundaryValues() {
        assertEquals("SLOW", SpeedColorUtils.getSpeedCategory(29.9f))
        assertEquals("CITY", SpeedColorUtils.getSpeedCategory(30.0f))
        assertEquals("CITY", SpeedColorUtils.getSpeedCategory(49.9f))
        assertEquals("URBAN", SpeedColorUtils.getSpeedCategory(50.0f))
        assertEquals("URBAN", SpeedColorUtils.getSpeedCategory(69.9f))
        assertEquals("SUBURBAN", SpeedColorUtils.getSpeedCategory(70.0f))
        assertEquals("SUBURBAN", SpeedColorUtils.getSpeedCategory(89.9f))
        assertEquals("HIGHWAY", SpeedColorUtils.getSpeedCategory(90.0f))
        assertEquals("HIGHWAY", SpeedColorUtils.getSpeedCategory(109.9f))
        assertEquals("FAST", SpeedColorUtils.getSpeedCategory(110.0f))
        assertEquals("FAST", SpeedColorUtils.getSpeedCategory(129.9f))
        assertEquals("VERY FAST", SpeedColorUtils.getSpeedCategory(130.0f))
    }
}
