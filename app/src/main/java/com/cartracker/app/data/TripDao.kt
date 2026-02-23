package com.cartracker.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert
    suspend fun insert(trip: Trip): Long

    @Update
    suspend fun update(trip: Trip)

    @Delete
    suspend fun delete(trip: Trip)

    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    fun getAllTrips(): Flow<List<Trip>>

    @Query("SELECT * FROM trips WHERE startTime >= :since ORDER BY startTime DESC")
    fun getTripsSince(since: Long): Flow<List<Trip>>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getTripById(id: Long): Trip?

    @Query("SELECT * FROM trips WHERE id = :id")
    fun getTripByIdFlow(id: Long): Flow<Trip?>

    @Query("SELECT * FROM trips WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveTrip(): Trip?

    @Query("SELECT * FROM trips WHERE startTime >= :since ORDER BY startTime DESC")
    suspend fun getTripsSinceSync(since: Long): List<Trip>

    @Query("DELETE FROM trips WHERE startTime < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT * FROM trips ORDER BY startTime DESC LIMIT 1")
    suspend fun getLastTrip(): Trip?

    @Query("SELECT COUNT(*) FROM trips WHERE startTime >= :since")
    suspend fun getTripCountSince(since: Long): Int

    @Query("SELECT SUM(distanceMeters) FROM trips WHERE startTime >= :since")
    suspend fun getTotalDistanceSince(since: Long): Double?
}
