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
 * Instrumented tests for Room database operations.
 * Runs on the connected Android device.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseTest {

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

    // ── Trip DAO Tests ──────────────────────────────────────────────

    @Test
    fun insertTrip_returnsValidId() = runBlocking {
        val trip = Trip(startTime = System.currentTimeMillis(), isActive = true)
        val id = tripDao.insert(trip)
        assertTrue("Trip ID should be > 0", id > 0)
    }

    @Test
    fun insertAndRetrieveTrip() = runBlocking {
        val now = System.currentTimeMillis()
        val trip = Trip(startTime = now, isActive = true, distanceMeters = 1500.0)
        val id = tripDao.insert(trip)

        val retrieved = tripDao.getTripById(id)
        assertNotNull("Retrieved trip should not be null", retrieved)
        assertEquals(id, retrieved!!.id)
        assertEquals(now, retrieved.startTime)
        assertTrue(retrieved.isActive)
        assertEquals(1500.0, retrieved.distanceMeters, 0.01)
    }

    @Test
    fun getActiveTrip_returnsOnlyActiveTrip() = runBlocking {
        tripDao.insert(Trip(startTime = 1000L, isActive = false, endTime = 2000L))
        tripDao.insert(Trip(startTime = 3000L, isActive = true))

        val active = tripDao.getActiveTrip()
        assertNotNull("Should find an active trip", active)
        assertTrue(active!!.isActive)
        assertEquals(3000L, active.startTime)
    }

    @Test
    fun getActiveTrip_returnsNullWhenNoneActive() = runBlocking {
        tripDao.insert(Trip(startTime = 1000L, isActive = false, endTime = 2000L))
        val active = tripDao.getActiveTrip()
        assertNull("Should be null when no active trips", active)
    }

    @Test
    fun updateTrip_updatesFields() = runBlocking {
        val id = tripDao.insert(Trip(startTime = 1000L, isActive = true))
        val original = tripDao.getTripById(id)!!

        tripDao.update(original.copy(
            endTime = 5000L,
            isActive = false,
            distanceMeters = 2500.0,
            maxSpeedKmh = 95f,
            avgSpeedKmh = 55f,
            durationMillis = 4000L
        ))

        val updated = tripDao.getTripById(id)!!
        assertFalse(updated.isActive)
        assertEquals(5000L, updated.endTime)
        assertEquals(2500.0, updated.distanceMeters, 0.01)
        assertEquals(95f, updated.maxSpeedKmh, 0.01f)
        assertEquals(55f, updated.avgSpeedKmh, 0.01f)
        assertEquals(4000L, updated.durationMillis)
    }

    @Test
    fun deleteTrip_removesTrip() = runBlocking {
        val id = tripDao.insert(Trip(startTime = 1000L, isActive = false))
        val trip = tripDao.getTripById(id)!!
        tripDao.delete(trip)
        val afterDelete = tripDao.getTripById(id)
        assertNull("Trip should be deleted", afterDelete)
    }

    @Test
    fun getAllTrips_returnsSortedByStartTimeDesc() = runBlocking {
        tripDao.insert(Trip(startTime = 1000L, isActive = false))
        tripDao.insert(Trip(startTime = 3000L, isActive = false))
        tripDao.insert(Trip(startTime = 2000L, isActive = false))

        val trips = tripDao.getAllTrips().first()
        assertEquals(3, trips.size)
        assertEquals(3000L, trips[0].startTime)
        assertEquals(2000L, trips[1].startTime)
        assertEquals(1000L, trips[2].startTime)
    }

    @Test
    fun getTripsSince_filtersCorrectly() = runBlocking {
        tripDao.insert(Trip(startTime = 1000L, isActive = false))
        tripDao.insert(Trip(startTime = 5000L, isActive = false))
        tripDao.insert(Trip(startTime = 9000L, isActive = false))

        val recentTrips = tripDao.getTripsSinceSync(4000L)
        assertEquals(2, recentTrips.size)
    }

    @Test
    fun getLastTrip_returnsNewest() = runBlocking {
        tripDao.insert(Trip(startTime = 1000L, isActive = false))
        tripDao.insert(Trip(startTime = 5000L, isActive = false))

        val last = tripDao.getLastTrip()
        assertNotNull(last)
        assertEquals(5000L, last!!.startTime)
    }

    @Test
    fun getTripCountSince_returnsCorrectCount() = runBlocking {
        tripDao.insert(Trip(startTime = 1000L, isActive = false))
        tripDao.insert(Trip(startTime = 3000L, isActive = false))
        tripDao.insert(Trip(startTime = 5000L, isActive = false))

        assertEquals(2, tripDao.getTripCountSince(2500L))
        assertEquals(3, tripDao.getTripCountSince(500L))
        assertEquals(0, tripDao.getTripCountSince(6000L))
    }

    @Test
    fun getTotalDistanceSince_sumsCorrectly() = runBlocking {
        tripDao.insert(Trip(startTime = 1000L, isActive = false, distanceMeters = 1000.0))
        tripDao.insert(Trip(startTime = 3000L, isActive = false, distanceMeters = 2500.0))
        tripDao.insert(Trip(startTime = 5000L, isActive = false, distanceMeters = 500.0))

        val total = tripDao.getTotalDistanceSince(2000L)
        assertEquals(3000.0, total!!, 0.01)
    }

    @Test
    fun deleteOlderThan_removesOldTrips() = runBlocking {
        tripDao.insert(Trip(startTime = 1000L, isActive = false))
        tripDao.insert(Trip(startTime = 5000L, isActive = false))
        tripDao.insert(Trip(startTime = 9000L, isActive = false))

        tripDao.deleteOlderThan(4000L)

        val remaining = tripDao.getAllTrips().first()
        assertEquals(2, remaining.size)
        assertTrue(remaining.all { it.startTime >= 4000L })
    }

    // ── LocationPoint DAO Tests ─────────────────────────────────────

    @Test
    fun insertLocationPoint_returnsValidId() = runBlocking {
        val tripId = tripDao.insert(Trip(startTime = 1000L, isActive = true))
        val point = createPoint(tripId, 48.8566, 2.3522, 60f, 1000L)
        val id = locationPointDao.insert(point)
        assertTrue("Point ID should be > 0", id > 0)
    }

    @Test
    fun getPointsForTrip_returnsOrderedByTimestamp() = runBlocking {
        val tripId = tripDao.insert(Trip(startTime = 1000L, isActive = true))

        locationPointDao.insert(createPoint(tripId, 48.856, 2.352, 50f, 3000L))
        locationPointDao.insert(createPoint(tripId, 48.857, 2.353, 55f, 1000L))
        locationPointDao.insert(createPoint(tripId, 48.858, 2.354, 60f, 2000L))

        val points = locationPointDao.getPointsForTrip(tripId).first()
        assertEquals(3, points.size)
        assertEquals(1000L, points[0].timestamp)
        assertEquals(2000L, points[1].timestamp)
        assertEquals(3000L, points[2].timestamp)
    }

    @Test
    fun getPointsForTrip_doesNotMixTrips() = runBlocking {
        val tripId1 = tripDao.insert(Trip(startTime = 1000L, isActive = false))
        val tripId2 = tripDao.insert(Trip(startTime = 2000L, isActive = true))

        locationPointDao.insert(createPoint(tripId1, 48.856, 2.352, 50f, 1000L))
        locationPointDao.insert(createPoint(tripId1, 48.857, 2.353, 55f, 2000L))
        locationPointDao.insert(createPoint(tripId2, 48.858, 2.354, 60f, 3000L))

        val trip1Points = locationPointDao.getPointsForTrip(tripId1).first()
        assertEquals(2, trip1Points.size)

        val trip2Points = locationPointDao.getPointsForTrip(tripId2).first()
        assertEquals(1, trip2Points.size)
    }

    @Test
    fun getLastPointForTrip_returnsNewest() = runBlocking {
        val tripId = tripDao.insert(Trip(startTime = 1000L, isActive = true))

        locationPointDao.insert(createPoint(tripId, 48.856, 2.352, 50f, 1000L))
        locationPointDao.insert(createPoint(tripId, 48.857, 2.353, 55f, 3000L))
        locationPointDao.insert(createPoint(tripId, 48.858, 2.354, 60f, 2000L))

        val last = locationPointDao.getLastPointForTrip(tripId)
        assertNotNull(last)
        assertEquals(3000L, last!!.timestamp)
        assertEquals(55f, last.speedKmh, 0.01f)
    }

    @Test
    fun getMaxSpeedForTrip_returnsCorrectMax() = runBlocking {
        val tripId = tripDao.insert(Trip(startTime = 1000L, isActive = true))

        locationPointDao.insert(createPoint(tripId, 48.856, 2.352, 50f, 1000L))
        locationPointDao.insert(createPoint(tripId, 48.857, 2.353, 120f, 2000L))
        locationPointDao.insert(createPoint(tripId, 48.858, 2.354, 80f, 3000L))

        val maxSpeed = locationPointDao.getMaxSpeedForTrip(tripId)
        assertEquals(120f, maxSpeed!!, 0.01f)
    }

    @Test
    fun getAvgSpeedForTrip_excludesZeroSpeed() = runBlocking {
        val tripId = tripDao.insert(Trip(startTime = 1000L, isActive = true))

        locationPointDao.insert(createPoint(tripId, 48.856, 2.352, 0f, 1000L))  // stationary
        locationPointDao.insert(createPoint(tripId, 48.857, 2.353, 60f, 2000L))
        locationPointDao.insert(createPoint(tripId, 48.858, 2.354, 100f, 3000L))

        val avg = locationPointDao.getAvgSpeedForTrip(tripId)
        // Average of 60 and 100 (excludes 0) = 80
        assertEquals(80f, avg!!, 0.01f)
    }

    @Test
    fun getPointCountForTrip_returnsCorrectCount() = runBlocking {
        val tripId = tripDao.insert(Trip(startTime = 1000L, isActive = true))

        locationPointDao.insert(createPoint(tripId, 48.856, 2.352, 50f, 1000L))
        locationPointDao.insert(createPoint(tripId, 48.857, 2.353, 55f, 2000L))

        assertEquals(2, locationPointDao.getPointCountForTrip(tripId))
    }

    @Test
    fun deleteOlderThan_removesOldPoints() = runBlocking {
        val tripId = tripDao.insert(Trip(startTime = 1000L, isActive = true))

        locationPointDao.insert(createPoint(tripId, 48.856, 2.352, 50f, 1000L))
        locationPointDao.insert(createPoint(tripId, 48.857, 2.353, 55f, 3000L))
        locationPointDao.insert(createPoint(tripId, 48.858, 2.354, 60f, 5000L))

        locationPointDao.deleteOlderThan(2500L)

        val remaining = locationPointDao.getPointsForTrip(tripId).first()
        assertEquals(2, remaining.size)
        assertTrue(remaining.all { it.timestamp >= 2500L })
    }

    @Test
    fun cascadeDelete_removesPointsWhenTripDeleted() = runBlocking {
        val tripId = tripDao.insert(Trip(startTime = 1000L, isActive = false))
        locationPointDao.insert(createPoint(tripId, 48.856, 2.352, 50f, 1000L))
        locationPointDao.insert(createPoint(tripId, 48.857, 2.353, 55f, 2000L))

        val count = locationPointDao.getPointCountForTrip(tripId)
        assertEquals(2, count)

        val trip = tripDao.getTripById(tripId)!!
        tripDao.delete(trip)

        val afterDelete = locationPointDao.getPointCountForTrip(tripId)
        assertEquals("Points should be cascade-deleted", 0, afterDelete)
    }

    @Test
    fun insertAll_batchInsert() = runBlocking {
        val tripId = tripDao.insert(Trip(startTime = 1000L, isActive = true))
        val points = listOf(
            createPoint(tripId, 48.856, 2.352, 50f, 1000L),
            createPoint(tripId, 48.857, 2.353, 55f, 2000L),
            createPoint(tripId, 48.858, 2.354, 60f, 3000L)
        )
        locationPointDao.insertAll(points)
        assertEquals(3, locationPointDao.getPointCountForTrip(tripId))
    }

    // ── Helper ──────────────────────────────────────────────────────

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
