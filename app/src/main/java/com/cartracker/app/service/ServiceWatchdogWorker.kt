package com.cartracker.app.service

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Periodic WorkManager worker that checks if LocationTrackingService is running.
 * If the service has been killed (by system, battery optimization, or OEM kill),
 * this worker restarts it. Runs every 15 minutes (WorkManager minimum interval).
 */
class ServiceWatchdogWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        private const val TAG = "ServiceWatchdog"
    }

    override fun doWork(): Result {
        Log.d(TAG, "Watchdog checking service status...")

        // Only restart if we have location permission
        val hasFineLocation = ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFineLocation && !hasCoarseLocation) {
            Log.w(TAG, "No location permission - skipping service restart")
            return Result.success()
        }

        if (!isServiceRunning()) {
            Log.w(TAG, "Service not running - restarting!")
            LocationTrackingService.start(applicationContext)
        } else {
            Log.d(TAG, "Service is running - all good")
        }

        return Result.success()
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val activityManager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // getRunningServices is deprecated but still works for checking own services
        for (service in activityManager.getRunningServices(Integer.MAX_VALUE)) {
            if (LocationTrackingService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
