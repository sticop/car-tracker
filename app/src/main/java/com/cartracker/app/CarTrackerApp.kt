package com.cartracker.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.preference.PreferenceManager
import com.cartracker.app.data.AppDatabase
import org.osmdroid.config.Configuration

class CarTrackerApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()

        // Initialize osmdroid BEFORE any MapView is created
        Configuration.getInstance().apply {
            load(this@CarTrackerApp, PreferenceManager.getDefaultSharedPreferences(this@CarTrackerApp))
            userAgentValue = packageName
            osmdroidBasePath = filesDir
            osmdroidTileCache = java.io.File(cacheDir, "osmdroid")
        }

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val trackingChannel = NotificationChannel(
                TRACKING_CHANNEL_ID,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Car Tracker is recording your route"
                setShowBadge(false)
            }

            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Trip start/stop alerts"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(trackingChannel)
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    companion object {
        const val TRACKING_CHANNEL_ID = "tracking_channel"
        const val ALERT_CHANNEL_ID = "alert_channel"
        const val TRACKING_NOTIFICATION_ID = 1001
    }
}
