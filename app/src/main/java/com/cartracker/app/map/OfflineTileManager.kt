package com.cartracker.app.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.MapTileIndex
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages offline map tile caching.
 * When internet is available, downloads tiles around the user's location
 * at multiple zoom levels so the map works without internet.
 */
object OfflineTileManager {
    private const val TAG = "OfflineTileManager"

    // Zoom levels to cache (10=city overview, 17=street level detail)
    private const val MIN_ZOOM = 10
    private const val MAX_ZOOM = 17

    // Radius around user location to cache (in degrees, ~5km at equator for 0.05)
    private const val CACHE_RADIUS_DEG = 0.05 // ~5km

    // Maximum tiles per download session to avoid excessive bandwidth
    private const val MAX_TILES_PER_SESSION = 500

    // Track if we're currently downloading
    @Volatile
    private var isDownloading = false

    // Last cached location to avoid re-downloading
    private var lastCachedLat: Double? = null
    private var lastCachedLon: Double? = null
    private const val MIN_DISTANCE_FOR_RECACHE_DEG = 0.01 // ~1km

    /**
     * Cache tiles around the given location if internet is available.
     * Call this whenever we get a new location fix.
     */
    fun cacheTilesAroundLocation(
        context: Context,
        latitude: Double,
        longitude: Double,
        scope: CoroutineScope
    ) {
        // Don't start multiple downloads
        if (isDownloading) return

        // Check if we've already cached this area
        val lastLat = lastCachedLat
        val lastLon = lastCachedLon
        if (lastLat != null && lastLon != null) {
            val dist = Math.sqrt(
                Math.pow(latitude - lastLat, 2.0) + Math.pow(longitude - lastLon, 2.0)
            )
            if (dist < MIN_DISTANCE_FOR_RECACHE_DEG) {
                return // Already cached this area
            }
        }

        // Check internet connectivity
        if (!isNetworkAvailable(context)) {
            Log.d(TAG, "No internet, skipping tile cache")
            return
        }

        isDownloading = true
        scope.launch(Dispatchers.IO) {
            try {
                val tileCache = getTileCacheDir(context)
                val bbox = BoundingBox(
                    latitude + CACHE_RADIUS_DEG,
                    longitude + CACHE_RADIUS_DEG,
                    latitude - CACHE_RADIUS_DEG,
                    longitude - CACHE_RADIUS_DEG
                )

                var downloadedCount = 0

                for (zoom in MIN_ZOOM..MAX_ZOOM) {
                    if (downloadedCount >= MAX_TILES_PER_SESSION) break

                    val minTileX = lon2tile(bbox.lonWest, zoom)
                    val maxTileX = lon2tile(bbox.lonEast, zoom)
                    val minTileY = lat2tile(bbox.latNorth, zoom) // note: north has smaller Y
                    val maxTileY = lat2tile(bbox.latSouth, zoom)

                    for (x in minTileX..maxTileX) {
                        for (y in minTileY..maxTileY) {
                            if (downloadedCount >= MAX_TILES_PER_SESSION) break

                            val tileFile = File(tileCache, "$zoom/$x/$y.png")
                            if (tileFile.exists() && !isTileExpired(tileFile)) {
                                continue // Already cached and not expired
                            }

                            if (downloadTile(zoom, x, y, tileFile)) {
                                downloadedCount++
                            }

                            // Small delay to be polite to tile servers
                            delay(50)
                        }
                    }
                }

                lastCachedLat = latitude
                lastCachedLon = longitude
                Log.d(TAG, "Cached $downloadedCount tiles around ($latitude, $longitude)")
            } catch (e: Exception) {
                Log.e(TAG, "Error caching tiles", e)
            } finally {
                isDownloading = false
            }
        }
    }

    /**
     * Download a single tile from OpenStreetMap
     */
    private fun downloadTile(zoom: Int, x: Int, y: Int, destFile: File): Boolean {
        try {
            // MAPNIK tile URL pattern
            val url = "https://tile.openstreetmap.org/$zoom/$x/$y.png"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "CarTracker/1.0")
            }

            if (connection.responseCode == 200) {
                destFile.parentFile?.mkdirs()
                connection.inputStream.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to download tile $zoom/$x/$y: ${e.message}")
        }
        return false
    }

    /**
     * Check if a cached tile is older than 30 days
     */
    private fun isTileExpired(file: File): Boolean {
        val age = System.currentTimeMillis() - file.lastModified()
        return age > 30L * 24 * 60 * 60 * 1000 // 30 days
    }

    /**
     * Get the tile cache directory used by osmdroid
     */
    fun getTileCacheDir(context: Context): File {
        return File(context.cacheDir, "osmdroid/tiles/Mapnik")
    }

    /**
     * Get total cache size in MB
     */
    fun getCacheSizeMB(context: Context): Double {
        val dir = File(context.cacheDir, "osmdroid")
        return if (dir.exists()) {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024.0 * 1024.0)
        } else 0.0
    }

    /**
     * Check if the device has internet connectivity
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            return networkInfo?.isConnected == true
        }
    }

    // Tile math: convert lat/lon to tile coordinates
    private fun lon2tile(lon: Double, zoom: Int): Int {
        return ((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()
            .coerceIn(0, (1 shl zoom) - 1)
    }

    private fun lat2tile(lat: Double, zoom: Int): Int {
        val latRad = Math.toRadians(lat)
        return ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * (1 shl zoom)).toInt()
            .coerceIn(0, (1 shl zoom) - 1)
    }
}
