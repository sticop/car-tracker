package com.cartracker.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.cartracker.app.service.LocationTrackingService
import com.cartracker.app.ui.theme.CarTrackerTheme

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocation = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineLocation || coarseLocation) {
            // Got permission - immediately try to get location
            getQuickLocationFix()

            // Now request background location
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestBackgroundLocation()
            } else {
                startTracking()
            }
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestNotificationPermission()
        } else {
            // Can still track in foreground
            startTracking()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        startTracking()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        checkAndRequestPermissions()

        setContent {
            CarTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CarTrackerNavHost(viewModel = viewModel)
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        when {
            hasLocationPermission() -> {
                // Permission already granted - get location immediately
                getQuickLocationFix()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
                    requestBackgroundLocation()
                } else {
                    requestNotificationPermission()
                }
            }
            else -> {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }

    /**
     * Get location fix directly from Activity (faster than waiting for service to start).
     * Pushes the location to the service's static StateFlow so the map shows it immediately.
     */
    private fun getQuickLocationFix() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager

            // Try all providers for last known location
            val candidates = listOfNotNull(
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER),
                lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            )
            val bestLoc = candidates.maxByOrNull { it.time }
            if (bestLoc != null) {
                Log.d("MainActivity", "Quick fix from last known: ${bestLoc.latitude}, ${bestLoc.longitude}")
                LocationTrackingService.updateLocationFromActivity(bestLoc)
            }

            // Also request a fast single fix
            val quickListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    Log.d("MainActivity", "Quick single fix: ${location.latitude}, ${location.longitude}")
                    LocationTrackingService.updateLocationFromActivity(location)
                    lm.removeUpdates(this)
                }
                @Deprecated("Deprecated in API level 29")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            for (provider in lm.getProviders(true)) {
                try {
                    lm.requestSingleUpdate(provider, quickListener, Looper.getMainLooper())
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Quick location fix failed", e)
        }
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startTracking()
    }

    private fun startTracking() {
        LocationTrackingService.start(this)
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStats()
    }
}
