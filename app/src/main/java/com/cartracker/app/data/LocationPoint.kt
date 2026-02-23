package com.cartracker.app.data

import androidx.room.*

@Entity(
    tableName = "location_points",
    foreignKeys = [
        ForeignKey(
            entity = Trip::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tripId"), Index("timestamp")]
)
data class LocationPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val latitude: Double,
    val longitude: Double,
    val speed: Float,           // m/s
    val speedKmh: Float,        // km/h
    val altitude: Double,
    val bearing: Float,
    val accuracy: Float,
    val timestamp: Long,        // epoch millis
    val address: String? = null
)
