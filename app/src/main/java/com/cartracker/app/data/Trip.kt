package com.cartracker.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "trips",
    indices = [Index("startTime"), Index("endTime")]
)
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,            // epoch millis
    val endTime: Long? = null,      // null if trip is ongoing
    val startAddress: String? = null,
    val endAddress: String? = null,
    val distanceMeters: Double = 0.0,
    val maxSpeedKmh: Float = 0f,
    val avgSpeedKmh: Float = 0f,
    val durationMillis: Long = 0,
    val isActive: Boolean = true
)
