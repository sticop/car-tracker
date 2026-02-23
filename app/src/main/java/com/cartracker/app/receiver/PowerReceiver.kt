package com.cartracker.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cartracker.app.service.LocationTrackingService

class PowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                Log.d("PowerReceiver", "Power connected - starting tracking service")
                LocationTrackingService.start(context)
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                Log.d("PowerReceiver", "Power disconnected - tablet may be removed from car")
                // Keep service running but it will use battery-saving mode
                // Don't stop - the user might want to track even unplugged briefly
            }
        }
    }
}
