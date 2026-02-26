package com.cartracker.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.preference.PreferenceManager
import com.cartracker.app.data.AppDatabase
import org.osmdroid.config.Configuration

class CarTrackerApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()

        // Initialize osmdroid BEFORE any MapView is created
        Configuration.getInstance().apply {
            load(this@CarTrackerApp, PreferenceManager.getDefaultSharedPreferences(this@CarTrackerApp))
            userAgentValue = "$packageName/1.0"
            osmdroidBasePath = filesDir
            osmdroidTileCache = java.io.File(cacheDir, "osmdroid")

            // Offline tile caching configuration
            // Allow large tile cache (500 MB) so tiles persist for offline use
            tileFileSystemCacheMaxBytes = 500L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 400L * 1024 * 1024

            // Keep cached tiles for 30 days
            expirationOverrideDuration = 30L * 24 * 60 * 60 * 1000

            // Tile download quality settings
            tileDownloadThreads = 4.toShort()       // Parallel tile downloads for faster loading
            tileDownloadMaxQueueSize = 40.toShort()  // Larger queue for smoother panning

            // Set HTTP timeouts for tile downloads
            setHttpProxy(null) // no proxy
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
