package com.cartracker.app.util

object SpeedColorUtils {
    // Uber-tinted speed palette: green → yellow → orange → red → purple
    fun getColorForSpeed(speedKmh: Float): Int {
        return when {
            speedKmh < 30 -> 0xFF06C167.toInt()   // Uber Green - slow
            speedKmh < 50 -> 0xFF7EC489.toInt()   // Mint - city
            speedKmh < 70 -> 0xFFFFC043.toInt()   // Uber Yellow - urban
            speedKmh < 90 -> 0xFFFF9500.toInt()   // Amber - suburban
            speedKmh < 110 -> 0xFFFF6B00.toInt()  // Deep orange - highway
            speedKmh < 130 -> 0xFFE11900.toInt()  // Uber Red - fast
            else -> 0xFFCB2BD5.toInt()             // Purple - very fast
        }
    }

    fun getSpeedCategory(speedKmh: Float): String {
        return when {
            speedKmh < 30 -> "SLOW"
            speedKmh < 50 -> "CITY"
            speedKmh < 70 -> "URBAN"
            speedKmh < 90 -> "SUBURBAN"
            speedKmh < 110 -> "HIGHWAY"
            speedKmh < 130 -> "FAST"
            else -> "VERY FAST"
        }
    }
}
