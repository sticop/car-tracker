package com.cartracker.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationPointDao {
    @Insert
    suspend fun insert(point: LocationPoint): Long

    @Insert
    suspend fun insertAll(points: List<LocationPoint>)

    @Query("SELECT * FROM location_points WHERE tripId = :tripId ORDER BY timestamp ASC")
    fun getPointsForTrip(tripId: Long): Flow<List<LocationPoint>>

    @Query("SELECT * FROM location_points WHERE tripId = :tripId ORDER BY timestamp ASC")
    suspend fun getPointsForTripSync(tripId: Long): List<LocationPoint>

    @Query("SELECT * FROM location_points WHERE tripId = :tripId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastPointForTrip(tripId: Long): LocationPoint?

    @Query("SELECT * FROM location_points WHERE timestamp >= :since ORDER BY timestamp ASC LIMIT 10000")
    fun getPointsSince(since: Long): Flow<List<LocationPoint>>

    @Query("DELETE FROM location_points WHERE timestamp < :before")
    @Transaction
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT MAX(speedKmh) FROM location_points WHERE tripId = :tripId")
    suspend fun getMaxSpeedForTrip(tripId: Long): Float?

    @Query("SELECT AVG(speedKmh) FROM location_points WHERE tripId = :tripId AND speedKmh > 0")
    suspend fun getAvgSpeedForTrip(tripId: Long): Float?

    @Query("SELECT COUNT(*) FROM location_points WHERE tripId = :tripId")
    suspend fun getPointCountForTrip(tripId: Long): Int
}
