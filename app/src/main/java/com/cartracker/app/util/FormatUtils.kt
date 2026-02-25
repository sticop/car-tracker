package com.cartracker.app.util

import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

object FormatUtils {
    // Cached formatters (SimpleDateFormat is not thread-safe, using ThreadLocal)
    private val timeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm", Locale.getDefault())
    }
    private val dateTimeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    }
    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault())
    }
    private val fullDateTimeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("EEEE, MMMM dd yyyy 'at' HH:mm:ss", Locale.getDefault())
    }

    fun formatSpeed(speedKmh: Float): String {
        return String.format("%.0f km/h", speedKmh)
    }

    fun formatDistance(meters: Double): String {
        return if (meters < 1000) {
            String.format("%.0f m", meters)
        } else {
            String.format("%.1f km", meters / 1000)
        }
    }

    fun formatDuration(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60

        return when {
            hours > 0 -> String.format("%dh %dm", hours, minutes)
            minutes > 0 -> String.format("%dm %ds", minutes, seconds)
            else -> String.format("%ds", seconds)
        }
    }

    fun formatTime(timestamp: Long): String {
        return timeFormat.get()!!.format(Date(timestamp))
    }

    fun formatDateTime(timestamp: Long): String {
        return dateTimeFormat.get()!!.format(Date(timestamp))
    }

    fun formatDate(timestamp: Long): String {
        return dateFormat.get()!!.format(Date(timestamp))
    }

    fun formatFullDateTime(timestamp: Long): String {
        return fullDateTimeFormat.get()!!.format(Date(timestamp))
    }

    fun isToday(timestamp: Long): Boolean {
        val cal = Calendar.getInstance()
        val today = Calendar.getInstance()
        cal.timeInMillis = timestamp
        return cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
                cal.get(Calendar.YEAR) == today.get(Calendar.YEAR)
    }

    fun isYesterday(timestamp: Long): Boolean {
        val cal = Calendar.getInstance()
        val yesterday = Calendar.getInstance()
        yesterday.add(Calendar.DAY_OF_YEAR, -1)
        cal.timeInMillis = timestamp
        return cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR) &&
                cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR)
    }

    fun getRelativeDay(timestamp: Long): String {
        return when {
            isToday(timestamp) -> "Today"
            isYesterday(timestamp) -> "Yesterday"
            else -> formatDate(timestamp)
        }
    }
}
