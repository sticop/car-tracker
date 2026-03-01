package com.cartracker.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cartracker.app.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/**
 * Integration tests verifying the full trip lifecycle:
 * creating trips, recording location points, computing stats,
 * ending trips, and data retention cleanup.
 *
 * Runs on the connected Android device.
 */
@RunWith(AndroidJUnit4::class)
class LocationDataIntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var tripDao: TripDao
    private lateinit var locationPointDao: LocationPointDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tripDao = db.tripDao()
        locationPointDao = db.locationPointDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Full Trip Lifecycle ─────────────────────────────────────────

    @Test
    fun fullTripLifecycle_startRecordEnd() = runBlocking {
        // 1. Start a trip
        val startTime = System.currentTimeMillis()
        val tripId = tripDao.insert(Trip(
            startTime = startTime,
            isActive = true
        ))
        assertTrue(tripId > 0)

        // 2. Record location points along a simulated route
        val routePoints = listOf(
            RoutePoint(48.8566, 2.3522, 0f, 0L),      // Paris - start (stationary)
            RoutePoint(48.8570, 2.3530, 25f, 2000L),   // Starting to move
            RoutePoint(48.8580, 2.3545, 50f, 4000L),   // City speed
            RoutePoint(48.8600, 2.3570, 75f, 6000L),   // Picking up speed
            RoutePoint(48.8630, 2.3600, 110f, 8000L),  // Highway
            RoutePoint(48.8670, 2.3640, 120f, 10000L), // Cruising
            RoutePoint(48.8700, 2.3670, 90f, 12000L),  // Slowing down
            RoutePoint(48.8720, 2.3690, 40f, 14000L),  // City speed
            RoutePoint(48.8730, 2.3700, 10f, 16000L),  // Nearly stopped
            RoutePoint(48.8735, 2.3705, 0f, 18000L),   // Parked
        )

        for (rp in routePoints) {
            locationPointDao.insert(LocationPoint(
                tripId = tripId,
                latitude = rp.lat,
                longitude = rp.lon,
                speed = rp.speedKmh / 3.6f,
                speedKmh = rp.speedKmh,
                altitude = 35.0,
                bearing = 45f,
                accuracy = 10f,
                timestamp = startTime + rp.offsetMs
            ))
        }

        // 3. Verify point storage
        val storedPoints = locationPointDao.getPointsForTripSync(tripId)
        assertEquals(routePoints.size, storedPoints.size)

        // 4. Verify statistics
        val maxSpeed = locationPointDao.getMaxSpeedForTrip(tripId)
        assertEquals(120f, maxSpeed!!, 0.01f)

        val avgSpeed = locationPointDao.getAvgSpeedForTrip(tripId)
        assertNotNull(avgSpeed)
        assertTrue("Avg speed should be > 0 (excludes zero-speed points)", avgSpeed!! > 0f)

        val pointCount = locationPointDao.getPointCountForTrip(tripId)
        assertEquals(10, pointCount)

        // 5. Verify last point
        val lastPoint = locationPointDao.getLastPointForTrip(tripId)
        assertNotNull(lastPoint)
        assertEquals(0f, lastPoint!!.speedKmh, 0.01f) // Should be the parked point

        // 6. End the trip
        val endTime = startTime + 18000L
        val trip = tripDao.getTripById(tripId)!!
        tripDao.update(trip.copy(
            endTime = endTime,
            isActive = false,
            distanceMeters = 2500.0,
            maxSpeedKmh = maxSpeed,
            avgSpeedKmh = avgSpeed,
            durationMillis = endTime - startTime
        ))

        // 7. Verify trip is no longer active
        val activeTrip = tripDao.getActiveTrip()
        assertNull("No trip should be active after ending", activeTrip)

        // 8. Verify completed trip data
        val completed = tripDao.getTripById(tripId)!!
        assertFalse(completed.isActive)
        assertEquals(endTime, completed.endTime)
        assertEquals(2500.0, completed.distanceMeters, 0.01)
        assertEquals(18000L, completed.durationMillis)
    }

    // ── Multiple Trips ──────────────────────────────────────────────

    @Test
    fun multipleTrips_independentStats() = runBlocking {
        val trip1Id = tripDao.insert(Trip(startTime = 1000L, isActive = false, endTime = 5000L))
        val trip2Id = tripDao.insert(Trip(startTime = 6000L, isActive = false, endTime = 10000L))

        // Trip 1: slow city driving
        locationPointDao.insertAll(listOf(
            createPoint(trip1Id, 48.856, 2.352, 30f, 1000L),
            createPoint(trip1Id, 48.857, 2.353, 40f, 2000L),
            createPoint(trip1Id, 48.858, 2.354, 35f, 3000L)
        ))

        // Trip 2: fast highway
        locationPointDao.insertAll(listOf(
            createPoint(trip2Id, 49.000, 2.500, 90f, 6000L),
            createPoint(trip2Id, 49.010, 2.510, 120f, 7000L),
            createPoint(trip2Id, 49.020, 2.520, 110f, 8000L)
        ))

        // Stats should be independent
        assertEquals(40f, locationPointDao.getMaxSpeedForTrip(trip1Id)!!, 0.01f)
        assertEquals(120f, locationPointDao.getMaxSpeedForTrip(trip2Id)!!, 0.01f)

        assertEquals(35f, locationPointDao.getAvgSpeedForTrip(trip1Id)!!, 0.01f)
        // Avg of 90, 120, 110 = 106.67
        assertEquals(106.67f, locationPointDao.getAvgSpeedForTrip(trip2Id)!!, 0.1f)
    }

    // ── Data Retention (30 days) ────────────────────────────────────

    @Test
    fun dataRetention_removesOldDataKeepsRecent() = runBlocking {
        val now = System.currentTimeMillis()
        val thirtyDaysAgo = now - 30L * 24 * 60 * 60 * 1000

        // Old trip (>30 days)
        val oldTripId = tripDao.insert(Trip(
            startTime = thirtyDaysAgo - 100000L,
            isActive = false,
            endTime = thirtyDaysAgo - 50000L,
            distanceMeters = 5000.0
        ))
        locationPointDao.insert(createPoint(oldTripId, 48.856, 2.352, 60f, thirtyDaysAgo - 100000L))
        locationPointDao.insert(createPoint(oldTripId, 48.857, 2.353, 65f, thirtyDaysAgo - 80000L))

        // Recent trip
        val newTripId = tripDao.insert(Trip(
            startTime = now - 3600000L,
            isActive = false,
            endTime = now,
            distanceMeters = 2000.0
        ))
        locationPointDao.insert(createPoint(newTripId, 49.000, 2.500, 80f, now - 3600000L))
        locationPointDao.insert(createPoint(newTripId, 49.010, 2.510, 85f, now))

        // Apply retention cleanup
        tripDao.deleteOlderThan(thirtyDaysAgo)
        locationPointDao.deleteOlderThan(thirtyDaysAgo)

        // Old trip should be gone
        assertNull(tripDao.getTripById(oldTripId))
        assertEquals(0, locationPointDao.getPointCountForTrip(oldTripId))

        // Recent trip should still exist
        assertNotNull(tripDao.getTripById(newTripId))
        assertEquals(2, locationPointDao.getPointCountForTrip(newTripId))
    }

    // ── Edge Cases ──────────────────────────────────────────────────

    @Test
    fun emptyTrip_noPoints() = runBlocking {
        val tripId = tripDao.insert(Trip(startTime = 1000L, isActive = true))

        assertNull(locationPointDao.getMaxSpeedForTrip(tripId))
        assertNull(locationPointDao.getAvgSpeedForTrip(tripId))
        assertNull(locationPointDao.getLastPointForTrip(tripId))
        assertEquals(0, locationPointDao.getPointCountForTrip(tripId))
    }

    @Test
    fun singlePointTrip() = runBlocking {
        val tripId = tripDao.insert(Trip(startTime = 1000L, isActive = true))
        locationPointDao.insert(createPoint(tripId, 48.856, 2.352, 50f, 1000L))

        assertEquals(50f, locationPointDao.getMaxSpeedForTrip(tripId)!!, 0.01f)
        assertEquals(50f, locationPointDao.getAvgSpeedForTrip(tripId)!!, 0.01f)
        assertEquals(1, locationPointDao.getPointCountForTrip(tripId))
    }

    @Test
    fun allZeroSpeedPoints_avgIsNull() = runBlocking {
        val tripId = tripDao.insert(Trip(startTime = 1000L, isActive = true))
        locationPointDao.insert(createPoint(tripId, 48.856, 2.352, 0f, 1000L))
        locationPointDao.insert(createPoint(tripId, 48.857, 2.353, 0f, 2000L))

        // AVG with WHERE speedKmh > 0 should return null since no qualifying rows
        val avg = locationPointDao.getAvgSpeedForTrip(tripId)
        assertNull("Avg speed should be null when all points have 0 speed", avg)
    }

    @Test
    fun highSpeedPoints_maxIsCorrect() = runBlocking {
        val tripId = tripDao.insert(Trip(startTime = 1000L, isActive = true))
        locationPointDao.insert(createPoint(tripId, 48.856, 2.352, 180f, 1000L))
        locationPointDao.insert(createPoint(tripId, 48.900, 2.400, 200f, 2000L))
        locationPointDao.insert(createPoint(tripId, 48.950, 2.450, 190f, 3000L))

        assertEquals(200f, locationPointDao.getMaxSpeedForTrip(tripId)!!, 0.01f)
    }

    @Test
    fun flowUpdates_whenNewPointInserted() = runBlocking {
        val tripId = tripDao.insert(Trip(startTime = 1000L, isActive = true))

        // Get initial state
        val initialPoints = locationPointDao.getPointsForTrip(tripId).first()
        assertEquals(0, initialPoints.size)

        // Insert a point
        locationPointDao.insert(createPoint(tripId, 48.856, 2.352, 50f, 1000L))
        val afterInsert = locationPointDao.getPointsForTrip(tripId).first()
        assertEquals(1, afterInsert.size)
    }

    // ── Helper ──────────────────────────────────────────────────────

    private data class RoutePoint(
        val lat: Double,
        val lon: Double,
        val speedKmh: Float,
        val offsetMs: Long
    )

    private fun createPoint(
        tripId: Long,
        lat: Double,
        lon: Double,
        speedKmh: Float,
        timestamp: Long
    ) = LocationPoint(
        tripId = tripId,
        latitude = lat,
        longitude = lon,
        speed = speedKmh / 3.6f,
        speedKmh = speedKmh,
        altitude = 50.0,
        bearing = 0f,
        accuracy = 10f,
        timestamp = timestamp
    )
}
