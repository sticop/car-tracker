package com.cartracker.app.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.min

/**
 * Manages automatic local tile caching + explicit city-level offline map downloads.
 */
object OfflineTileManager {
    private const val TAG = "OfflineTileManager"

    // Background auto-cache zoom levels around live location
    private const val MIN_ZOOM = 10
    private const val MAX_ZOOM = 17

    // Explicit city download zoom levels (full city, but size-bounded)
    private const val CITY_MIN_ZOOM = 10
    private const val CITY_MAX_ZOOM = 16

    // Radius around user location for background caching (~5 km)
    private const val CACHE_RADIUS_DEG = 0.05

    // Maximum tiles per background auto-cache session
    private const val MAX_TILES_PER_SESSION = 500

    // Bounding boxes smaller than this are expanded to city-sized area (~9 km)
    private const val MIN_CITY_SPAN_DEG = 0.08

    private const val CONNECTION_TIMEOUT_MS = 7000
    private const val READ_TIMEOUT_MS = 7000

    private const val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org"

    private data class TileSourceSpec(
        val cacheFolder: String,
        val label: String,
        val buildUrl: (zoom: Int, x: Int, y: Int) -> String
    )

    data class CityRegion(
        val name: String,
        val south: Double,
        val north: Double,
        val west: Double,
        val east: Double
    ) {
        fun normalized(): CityRegion {
            val clampedSouth = clampLatitude(min(south, north))
            val clampedNorth = clampLatitude(max(south, north))
            val clampedWest = clampLongitude(min(west, east))
            val clampedEast = clampLongitude(max(west, east))

            val latSpan = clampedNorth - clampedSouth
            val lonSpan = clampedEast - clampedWest

            val finalSouth = if (latSpan < MIN_CITY_SPAN_DEG) {
                clampLatitude(clampedSouth - (MIN_CITY_SPAN_DEG - latSpan) / 2.0)
            } else clampedSouth
            val finalNorth = if (latSpan < MIN_CITY_SPAN_DEG) {
                clampLatitude(clampedNorth + (MIN_CITY_SPAN_DEG - latSpan) / 2.0)
            } else clampedNorth

            val finalWest = if (lonSpan < MIN_CITY_SPAN_DEG) {
                clampLongitude(clampedWest - (MIN_CITY_SPAN_DEG - lonSpan) / 2.0)
            } else clampedWest
            val finalEast = if (lonSpan < MIN_CITY_SPAN_DEG) {
                clampLongitude(clampedEast + (MIN_CITY_SPAN_DEG - lonSpan) / 2.0)
            } else clampedEast

            return copy(
                south = min(finalSouth, finalNorth),
                north = max(finalSouth, finalNorth),
                west = min(finalWest, finalEast),
                east = max(finalWest, finalEast)
            )
        }
    }

    data class CityDownloadResult(
        val success: Boolean,
        val totalTiles: Int,
        val downloadedTiles: Int,
        val skippedTiles: Int,
        val failedTiles: Int,
        val errorMessage: String? = null
    )

    private val cartoVoyager = TileSourceSpec(
        cacheFolder = "CartoVoyager",
        label = "Detail"
    ) { zoom, x, y ->
        val server = serverFor(x, y)
        "https://$server.basemaps.cartocdn.com/rastertiles/voyager/$zoom/$x/$y@2x.png"
    }

    private val cartoDarkMatter = TileSourceSpec(
        cacheFolder = "CartoDarkMatter",
        label = "Dark"
    ) { zoom, x, y ->
        val server = serverFor(x, y)
        "https://$server.basemaps.cartocdn.com/dark_all/$zoom/$x/$y@2x.png"
    }

    // Keep both sources warm so either style works offline.
    private val backgroundSources = listOf(cartoVoyager, cartoDarkMatter)
    private val citySources = listOf(cartoVoyager, cartoDarkMatter)

    @Volatile
    private var isDownloading = false

    private var lastCachedLat: Double? = null
    private var lastCachedLon: Double? = null
    private const val MIN_DISTANCE_FOR_RECACHE_DEG = 0.01 // ~1km

    @Volatile
    private var lastGeocodeRequestAtMs: Long = 0L

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
        if (isDownloading) return

        val lastLat = lastCachedLat
        val lastLon = lastCachedLon
        if (lastLat != null && lastLon != null) {
            val dLat = latitude - lastLat
            val dLon = longitude - lastLon
            val dist = kotlin.math.sqrt((dLat * dLat) + (dLon * dLon))
            if (dist < MIN_DISTANCE_FOR_RECACHE_DEG) return
        }

        if (!isNetworkAvailable(context)) {
            Log.d(TAG, "No internet, skipping tile cache")
            return
        }

        isDownloading = true
        scope.launch(Dispatchers.IO) {
            try {
                val bbox = BoundingBox(
                    latitude + CACHE_RADIUS_DEG,
                    longitude + CACHE_RADIUS_DEG,
                    latitude - CACHE_RADIUS_DEG,
                    longitude - CACHE_RADIUS_DEG
                )

                var downloadedCount = 0

                for (source in backgroundSources) {
                    if (downloadedCount >= MAX_TILES_PER_SESSION) break

                    for (zoom in MIN_ZOOM..MAX_ZOOM) {
                        if (downloadedCount >= MAX_TILES_PER_SESSION) break

                        val minTileX = lon2tile(bbox.lonWest, zoom)
                        val maxTileX = lon2tile(bbox.lonEast, zoom)
                        val minTileY = lat2tile(bbox.latNorth, zoom)
                        val maxTileY = lat2tile(bbox.latSouth, zoom)

                        for (x in minTileX..maxTileX) {
                            for (y in minTileY..maxTileY) {
                                if (downloadedCount >= MAX_TILES_PER_SESSION) break

                                val tileFile = File(
                                    getTileCacheDir(context, source.cacheFolder),
                                    "$zoom/$x/$y.png"
                                )
                                if (tileFile.exists() && !isTileExpired(tileFile)) continue

                                if (downloadTile(source, zoom, x, y, tileFile)) {
                                    downloadedCount++
                                }

                                // Be polite to tile servers.
                                delay(30)
                            }
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

    suspend fun searchCityRegion(query: String): CityRegion? = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return@withContext null

        val encoded = URLEncoder.encode(normalizedQuery, Charsets.UTF_8.name())
        val url = "$NOMINATIM_BASE_URL/search?format=jsonv2&limit=1&addressdetails=1&q=$encoded"

        val body = fetchJson(url) ?: return@withContext null
        val arr = JSONArray(body)
        if (arr.length() == 0) return@withContext null

        parseCityRegion(arr.optJSONObject(0) ?: return@withContext null)
    }

    suspend fun reverseGeocodeCityRegion(latitude: Double, longitude: Double): CityRegion? =
        withContext(Dispatchers.IO) {
            val url = "$NOMINATIM_BASE_URL/reverse?format=jsonv2&zoom=10&addressdetails=1&lat=$latitude&lon=$longitude"
            val body = fetchJson(url) ?: return@withContext null
            val json = JSONObject(body)

            val fallbackBounds = syntheticBounds(latitude, longitude)
            parseCityRegion(json, fallbackBounds)
        }

    /**
     * Number of tiles needed for full city download across both map styles.
     */
    fun estimateCityTileCount(region: CityRegion): Int {
        val normalized = region.normalized()
        var total = 0L

        citySources.forEach { _ ->
            for (zoom in CITY_MIN_ZOOM..CITY_MAX_ZOOM) {
                val minX = lon2tile(normalized.west, zoom)
                val maxX = lon2tile(normalized.east, zoom)
                val minY = lat2tile(normalized.north, zoom)
                val maxY = lat2tile(normalized.south, zoom)
                val count = (maxX - minX + 1).toLong() * (maxY - minY + 1).toLong()
                total += count
            }
        }

        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Download an entire city area for offline use.
     */
    suspend fun downloadCityTiles(
        context: Context,
        region: CityRegion,
        onProgress: ((completed: Int, total: Int, stage: String) -> Unit)? = null
    ): CityDownloadResult = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable(context)) {
            return@withContext CityDownloadResult(
                success = false,
                totalTiles = 0,
                downloadedTiles = 0,
                skippedTiles = 0,
                failedTiles = 0,
                errorMessage = "No internet connection"
            )
        }

        val normalizedRegion = region.normalized()
        val totalTiles = estimateCityTileCount(normalizedRegion)
        var completed = 0
        var downloaded = 0
        var skipped = 0
        var failed = 0

        if (totalTiles == 0) {
            return@withContext CityDownloadResult(
                success = false,
                totalTiles = 0,
                downloadedTiles = 0,
                skippedTiles = 0,
                failedTiles = 0,
                errorMessage = "No tiles to download"
            )
        }

        for (source in citySources) {
            onProgress?.invoke(completed, totalTiles, "Downloading ${source.label} map")

            val sourceCacheDir = getTileCacheDir(context, source.cacheFolder)

            for (zoom in CITY_MIN_ZOOM..CITY_MAX_ZOOM) {
                val minX = lon2tile(normalizedRegion.west, zoom)
                val maxX = lon2tile(normalizedRegion.east, zoom)
                val minY = lat2tile(normalizedRegion.north, zoom)
                val maxY = lat2tile(normalizedRegion.south, zoom)

                for (x in minX..maxX) {
                    for (y in minY..maxY) {
                        val tileFile = File(sourceCacheDir, "$zoom/$x/$y.png")

                        if (tileFile.exists()) {
                            skipped++
                        } else if (downloadTile(source, zoom, x, y, tileFile)) {
                            downloaded++
                        } else {
                            failed++
                        }

                        completed++
                        if (completed % 25 == 0 || completed == totalTiles) {
                            onProgress?.invoke(completed, totalTiles, "Downloading ${source.label} map")
                        }

                        delay(10)
                    }
                }
            }
        }

        val availableTiles = downloaded + skipped
        val missingTiles = (totalTiles - availableTiles).coerceAtLeast(0)
        val toleratedMissing = max(1, (totalTiles * 0.02).toInt()) // tolerate <=2% transient misses
        val success = missingTiles <= toleratedMissing

        CityDownloadResult(
            success = success,
            totalTiles = totalTiles,
            downloadedTiles = downloaded,
            skippedTiles = skipped,
            failedTiles = failed,
            errorMessage = if (success) null
            else "Download incomplete ($missingTiles missing tiles). Retry to finish the city cache."
        )
    }

    /**
     * Delete cached tiles for a previously downloaded city region.
     */
    suspend fun deleteCityTiles(context: Context, region: CityRegion): Int = withContext(Dispatchers.IO) {
        val normalizedRegion = region.normalized()
        var deleted = 0

        for (source in citySources) {
            val sourceCacheDir = getTileCacheDir(context, source.cacheFolder)

            for (zoom in CITY_MIN_ZOOM..CITY_MAX_ZOOM) {
                val minX = lon2tile(normalizedRegion.west, zoom)
                val maxX = lon2tile(normalizedRegion.east, zoom)
                val minY = lat2tile(normalizedRegion.north, zoom)
                val maxY = lat2tile(normalizedRegion.south, zoom)

                for (x in minX..maxX) {
                    val xDir = File(sourceCacheDir, "$zoom/$x")

                    for (y in minY..maxY) {
                        val tileFile = File(xDir, "$y.png")
                        if (tileFile.exists() && tileFile.delete()) {
                            deleted++
                        }
                    }

                    if (xDir.exists() && xDir.listFiles().isNullOrEmpty()) {
                        xDir.delete()
                    }
                }

                val zoomDir = File(sourceCacheDir, "$zoom")
                if (zoomDir.exists() && zoomDir.listFiles().isNullOrEmpty()) {
                    zoomDir.delete()
                }
            }
        }

        deleted
    }

    private suspend fun fetchJson(url: String): String? = withContext(Dispatchers.IO) {
        try {
            throttleGeocodeRequests()

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                connectTimeout = CONNECTION_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "CarTracker/1.0 (offline-city-cache)")
                setRequestProperty("Accept", "application/json")
            }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Geocode request failed ($code): $url")
                return@withContext null
            }

            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Geocode request failed: ${e.message}")
            null
        }
    }

    private suspend fun throttleGeocodeRequests() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastGeocodeRequestAtMs
        val minDelayMs = 1100L // keep requests close to 1 req/sec
        if (elapsed < minDelayMs) {
            delay(minDelayMs - elapsed)
        }
        lastGeocodeRequestAtMs = System.currentTimeMillis()
    }

    private fun parseCityRegion(
        json: JSONObject,
        fallbackBounds: CityRegion? = null
    ): CityRegion? {
        val name = extractCityName(json)
        if (name.isBlank()) return null

        val bounds = parseBounds(json) ?: fallbackBounds
        val finalBounds = bounds ?: return null

        return CityRegion(
            name = name,
            south = finalBounds.south,
            north = finalBounds.north,
            west = finalBounds.west,
            east = finalBounds.east
        ).normalized()
    }

    private fun extractCityName(json: JSONObject): String {
        val address = json.optJSONObject("address")
        val candidates = listOf("city", "town", "village", "municipality", "county", "state", "name")
        for (key in candidates) {
            val value = address?.optString(key).orEmpty().trim()
            if (value.isNotBlank()) return value
        }

        val directName = json.optString("name").trim()
        if (directName.isNotBlank()) return directName

        return json.optString("display_name")
            .substringBefore(',')
            .trim()
    }

    private fun parseBounds(json: JSONObject): CityRegion? {
        val bbox = json.optJSONArray("boundingbox") ?: return null
        if (bbox.length() < 4) return null

        val south = bbox.optString(0).toDoubleOrNull() ?: return null
        val north = bbox.optString(1).toDoubleOrNull() ?: return null
        val west = bbox.optString(2).toDoubleOrNull() ?: return null
        val east = bbox.optString(3).toDoubleOrNull() ?: return null

        return CityRegion(
            name = "",
            south = south,
            north = north,
            west = west,
            east = east
        )
    }

    private fun syntheticBounds(latitude: Double, longitude: Double): CityRegion {
        val delta = 0.08
        return CityRegion(
            name = "",
            south = latitude - delta,
            north = latitude + delta,
            west = longitude - delta,
            east = longitude + delta
        )
    }

    /**
     * Download a single tile.
     */
    private fun downloadTile(
        source: TileSourceSpec,
        zoom: Int,
        x: Int,
        y: Int,
        destFile: File
    ): Boolean {
        return try {
            val url = source.buildUrl(zoom, x, y)
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                connectTimeout = CONNECTION_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "CarTracker/1.0")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                destFile.parentFile?.mkdirs()
                connection.inputStream.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to download tile ${source.cacheFolder} $zoom/$x/$y: ${e.message}")
            false
        }
    }

    /**
     * Check if a cached tile is older than 30 days.
     */
    private fun isTileExpired(file: File): Boolean {
        val age = System.currentTimeMillis() - file.lastModified()
        return age > 30L * 24 * 60 * 60 * 1000
    }

    /**
     * Tile cache directory for a given map source.
     */
    fun getTileCacheDir(
        context: Context,
        sourceFolder: String = "CartoDarkMatter"
    ): File {
        val tileRoot = configuredTileRoot(context)
        return File(tileRoot, sourceFolder)
    }

    /**
     * Get total osmdroid cache size in MB.
     */
    fun getCacheSizeMB(context: Context): Double {
        val root = configuredTileRoot(context).parentFile ?: configuredTileRoot(context)
        return if (root.exists()) {
            root.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024.0 * 1024.0)
        } else {
            0.0
        }
    }

    /**
     * Check internet connectivity.
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        @Suppress("DEPRECATION")
        val networkInfo = cm.activeNetworkInfo
        @Suppress("DEPRECATION")
        return networkInfo?.isConnected == true
    }

    private fun configuredTileRoot(context: Context): File {
        val configured = try {
            Configuration.getInstance().osmdroidTileCache
        } catch (_: Exception) {
            null
        }
        return configured ?: File(context.filesDir, "osmdroid/tiles")
    }

    private fun serverFor(x: Int, y: Int): String {
        val servers = arrayOf("a", "b", "c", "d")
        return servers[(x + y).mod(servers.size)]
    }

    // Tile math: convert lat/lon to tile coordinates.
    private fun lon2tile(lon: Double, zoom: Int): Int {
        return ((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()
            .coerceIn(0, (1 shl zoom) - 1)
    }

    private fun lat2tile(lat: Double, zoom: Int): Int {
        val latRad = Math.toRadians(lat)
        return ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) /
            2.0 * (1 shl zoom)).toInt()
            .coerceIn(0, (1 shl zoom) - 1)
    }

    private fun clampLatitude(value: Double): Double = value.coerceIn(-85.0, 85.0)

    private fun clampLongitude(value: Double): Double {
        return when {
            value < -180.0 -> -180.0
            value > 180.0 -> 180.0
            else -> value
        }
    }
}
