package com.cartracker.app.util

import com.google.android.gms.maps.model.LatLng

object SpeedColorUtils {
    // Returns a color based on speed (green -> yellow -> orange -> red)
    fun getColorForSpeed(speedKmh: Float): Int {
        return when {
            speedKmh < 30 -> 0xFF4CAF50.toInt()   // Green - slow
            speedKmh < 50 -> 0xFF8BC34A.toInt()    // Light green
            speedKmh < 70 -> 0xFFFFEB3B.toInt()    // Yellow
            speedKmh < 90 -> 0xFFFF9800.toInt()    // Orange
            speedKmh < 110 -> 0xFFFF5722.toInt()   // Deep orange
            speedKmh < 130 -> 0xFFF44336.toInt()   // Red
            else -> 0xFF9C27B0.toInt()              // Purple - very fast
        }
    }

    fun getSpeedCategory(speedKmh: Float): String {
        return when {
            speedKmh < 30 -> "City (slow)"
            speedKmh < 50 -> "City"
            speedKmh < 70 -> "Urban"
            speedKmh < 90 -> "Suburban"
            speedKmh < 110 -> "Highway"
            speedKmh < 130 -> "Fast Highway"
            else -> "Very Fast"
        }
    }
}
