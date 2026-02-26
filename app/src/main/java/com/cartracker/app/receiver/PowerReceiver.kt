package com.cartracker.app.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.cartracker.app.service.LocationTrackingService

class PowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                Log.d("PowerReceiver", "Power connected - ensuring tracking service is running")
                // Start/restart the service to ensure it picks up the new power state
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    LocationTrackingService.start(context)
                }
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                Log.d("PowerReceiver", "Power disconnected - service will switch to battery-optimized mode")
                // The service's internal powerStateReceiver handles the GPS interval switch.
                // We also poke the service to ensure it's alive and adapts.
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    LocationTrackingService.start(context)
                }
            }
        }
    }
}
