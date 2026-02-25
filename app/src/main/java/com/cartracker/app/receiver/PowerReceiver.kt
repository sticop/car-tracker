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
                Log.d("PowerReceiver", "Power connected - starting tracking service")
                // Only start service if we have location permission
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    LocationTrackingService.start(context)
                }
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                Log.d("PowerReceiver", "Power disconnected - tablet may be removed from car")
            }
        }
    }
}
