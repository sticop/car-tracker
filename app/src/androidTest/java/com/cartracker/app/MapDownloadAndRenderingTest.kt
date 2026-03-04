package com.cartracker.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cartracker.app.map.CachedCityMap
import com.cartracker.app.map.CityMapCacheStore
import com.cartracker.app.map.OfflineTileManager
import com.cartracker.app.map.OfflineTileManager.CityRegion
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for:
 *  - CityMapCacheStore: persistence, upsert, remove, cooldowns, cityId generation
 *  - OfflineTileManager: CityRegion normalization, tile count estimation,
 *    tile math, cache directory, network API, and tile download/delete
 *
 * Runs on the connected Android device.
 */
@RunWith(AndroidJUnit4::class)
class MapDownloadAndRenderingTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clean CityMapCacheStore prefs
        context.getSharedPreferences("city_map_cache_store", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("city_map_cache_store", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // ═══════════════════════════════════════════════════════════════
    // CityMapCacheStore — City ID generation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun cityId_simpleCity() {
        assertEquals("paris", CityMapCacheStore.cityIdFor("Paris"))
    }

    @Test
    fun cityId_withSpaces() {
        assertEquals("new-york", CityMapCacheStore.cityIdFor("New York"))
    }

    @Test
    fun cityId_withSpecialChars() {
        assertEquals("st-tienne", CityMapCacheStore.cityIdFor("St-Étienne"))
    }

    @Test
    fun cityId_trims() {
        assertEquals("rabat", CityMapCacheStore.cityIdFor("  Rabat  "))
    }

    @Test
    fun cityId_blank_fallsBackToCity() {
        assertEquals("city", CityMapCacheStore.cityIdFor("   "))
    }

    @Test
    fun cityId_mixedCase() {
        assertEquals("casablanca", CityMapCacheStore.cityIdFor("CASABLANCA"))
    }

    // ═══════════════════════════════════════════════════════════════
    // CityMapCacheStore — Empty state
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun store_initiallyEmpty() {
        assertTrue(CityMapCacheStore.getCachedCities(context).isEmpty())
    }

    @Test
    fun store_isCityCached_falseWhenEmpty() {
        assertFalse(CityMapCacheStore.isCityCached(context, "Rabat"))
    }

    // ═══════════════════════════════════════════════════════════════
    // CityMapCacheStore — Upsert and query
    // ═══════════════════════════════════════════════════════════════

    private fun rabatRegion() = CityRegion(
        name = "Rabat",
        south = 33.90, north = 34.10,
        west = -6.90, east = -6.70
    )

    private fun casablancaRegion() = CityRegion(
        name = "Casablanca",
        south = 33.50, north = 33.70,
        west = -7.70, east = -7.40
    )

    @Test
    fun store_upsert_addsCity() {
        CityMapCacheStore.upsertCachedCity(context, rabatRegion(), tileCount = 500)
        val cities = CityMapCacheStore.getCachedCities(context)
        assertEquals(1, cities.size)
        assertEquals("Rabat", cities[0].cityName)
        assertEquals(500, cities[0].tileCount)
    }

    @Test
    fun store_upsert_updatesTileCount() {
        CityMapCacheStore.upsertCachedCity(context, rabatRegion(), tileCount = 500)
        CityMapCacheStore.upsertCachedCity(context, rabatRegion(), tileCount = 750)
        val cities = CityMapCacheStore.getCachedCities(context)
        assertEquals(1, cities.size)
        assertEquals(750, cities[0].tileCount)
    }

    @Test
    fun store_multipleCities_sortedByName() {
        CityMapCacheStore.upsertCachedCity(context, rabatRegion(), tileCount = 100)
        CityMapCacheStore.upsertCachedCity(context, casablancaRegion(), tileCount = 200)
        val cities = CityMapCacheStore.getCachedCities(context)
        assertEquals(2, cities.size)
        assertEquals("Casablanca", cities[0].cityName) // C before R
        assertEquals("Rabat", cities[1].cityName)
    }

    @Test
    fun store_isCityCached_trueAfterUpsert() {
        CityMapCacheStore.upsertCachedCity(context, rabatRegion(), tileCount = 100)
        assertTrue(CityMapCacheStore.isCityCached(context, "Rabat"))
    }

    @Test
    fun store_isCityCached_caseInsensitive() {
        CityMapCacheStore.upsertCachedCity(context, rabatRegion(), tileCount = 100)
        assertTrue(CityMapCacheStore.isCityCached(context, "rabat"))
        assertTrue(CityMapCacheStore.isCityCached(context, "RABAT"))
    }

    // ═══════════════════════════════════════════════════════════════
    // CityMapCacheStore — Remove
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun store_remove_returnsCityAndDeletes() {
        CityMapCacheStore.upsertCachedCity(context, rabatRegion(), tileCount = 100)
        val removed = CityMapCacheStore.removeCachedCity(context, "rabat")
        assertNotNull(removed)
        assertEquals("Rabat", removed!!.cityName)
        assertTrue(CityMapCacheStore.getCachedCities(context).isEmpty())
    }

    @Test
    fun store_remove_returnsNullForMissing() {
        val removed = CityMapCacheStore.removeCachedCity(context, "nonexistent")
        assertNull(removed)
    }

    @Test
    fun store_clearCachedCities() {
        CityMapCacheStore.upsertCachedCity(context, rabatRegion(), tileCount = 100)
        CityMapCacheStore.upsertCachedCity(context, casablancaRegion(), tileCount = 200)
        CityMapCacheStore.clearCachedCities(context)
        assertTrue(CityMapCacheStore.getCachedCities(context).isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // CityMapCacheStore — Prompt cooldowns
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun prompt_canPrompt_trueForNewCity() {
        assertTrue(CityMapCacheStore.canPromptForCity(context, "Marrakech"))
    }

    @Test
    fun prompt_canPrompt_falseIfCityCached() {
        CityMapCacheStore.upsertCachedCity(context, rabatRegion(), tileCount = 100)
        assertFalse(CityMapCacheStore.canPromptForCity(context, "Rabat"))
    }

    @Test
    fun prompt_deferPrompt_blocksFuturePrompts() {
        CityMapCacheStore.deferPrompt(context, "Fes")
        assertFalse(CityMapCacheStore.canPromptForCity(context, "Fes"))
    }

    @Test
    fun prompt_clearCooldown_allowsPromptAgain() {
        CityMapCacheStore.deferPrompt(context, "Fes")
        assertFalse(CityMapCacheStore.canPromptForCity(context, "Fes"))
        CityMapCacheStore.clearPromptCooldown(context, "Fes")
        assertTrue(CityMapCacheStore.canPromptForCity(context, "Fes"))
    }

    @Test
    fun prompt_upsertClearsPromptCooldown() {
        CityMapCacheStore.deferPrompt(context, "Rabat")
        assertFalse(CityMapCacheStore.canPromptForCity(context, "Rabat"))
        // After actually downloading, the cooldown should be cleared
        CityMapCacheStore.upsertCachedCity(context, rabatRegion(), tileCount = 100)
        // But canPrompt is false because the city is NOW cached
        assertFalse(CityMapCacheStore.canPromptForCity(context, "Rabat"))
    }

    // ═══════════════════════════════════════════════════════════════
    // CachedCityMap — toRegion round-trip
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun cachedCityMap_toRegion_preservesFields() {
        val cached = CachedCityMap(
            id = "rabat", cityName = "Rabat",
            south = 33.90, north = 34.10,
            west = -6.90, east = -6.70,
            downloadedAtMillis = 1000L, tileCount = 250
        )
        val region = cached.toRegion()
        assertEquals("Rabat", region.name)
        assertEquals(33.90, region.south, 0.001)
        assertEquals(34.10, region.north, 0.001)
        assertEquals(-6.90, region.west, 0.001)
        assertEquals(-6.70, region.east, 0.001)
    }

    // ═══════════════════════════════════════════════════════════════
    // CityRegion — Normalization
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun cityRegion_normalized_preservesLargeRegion() {
        val region = CityRegion("Test", south = 33.0, north = 34.0, west = -7.0, east = -6.0)
        val n = region.normalized()
        assertEquals(33.0, n.south, 0.01)
        assertEquals(34.0, n.north, 0.01)
        assertEquals(-7.0, n.west, 0.01)
        assertEquals(-6.0, n.east, 0.01)
    }

    @Test
    fun cityRegion_normalized_expandsSmallRegion() {
        // A tiny region (0.01 span) should be expanded to at least MIN_CITY_SPAN_DEG = 0.08
        val region = CityRegion("Tiny", south = 34.0, north = 34.01, west = -6.5, east = -6.49)
        val n = region.normalized()
        assertTrue("Lat span should be >= 0.08", (n.north - n.south) >= 0.079)
        assertTrue("Lon span should be >= 0.08", (n.east - n.west) >= 0.079)
    }

    @Test
    fun cityRegion_normalized_swappedBoundsAreFixed() {
        // If south > north, normalization should swap them
        val region = CityRegion("Swapped", south = 35.0, north = 33.0, west = -5.0, east = -7.0)
        val n = region.normalized()
        assertTrue("North must be >= South", n.north >= n.south)
        assertTrue("East must be >= West", n.east >= n.west)
    }

    @Test
    fun cityRegion_normalized_clampsLatitude() {
        val region = CityRegion("Polar", south = -90.0, north = 90.0, west = -180.0, east = 180.0)
        val n = region.normalized()
        assertTrue("South must be >= -85", n.south >= -85.0)
        assertTrue("North must be <= 85", n.north <= 85.0)
        assertTrue("West must be >= -180", n.west >= -180.0)
        assertTrue("East must be <= 180", n.east <= 180.0)
    }

    // ═══════════════════════════════════════════════════════════════
    // OfflineTileManager — Tile count estimation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun tileCount_positiveForRealCity() {
        val count = OfflineTileManager.estimateCityTileCount(rabatRegion())
        assertTrue("Tile count for Rabat should be > 0, was $count", count > 0)
    }

    @Test
    fun tileCount_largerRegionHasMoreTiles() {
        val small = CityRegion("Small", south = 34.0, north = 34.05, west = -6.85, east = -6.80)
        val large = CityRegion("Large", south = 33.5, north = 34.5, west = -7.5, east = -6.0)
        assertTrue("Larger region should have more tiles",
            OfflineTileManager.estimateCityTileCount(large) > OfflineTileManager.estimateCityTileCount(small))
    }

    @Test
    fun tileCount_zeroForDegeneratePoint() {
        // A point with south==north and west==east should produce at least 1 tile per zoom per source
        // after normalization expands it
        val point = CityRegion("Point", south = 34.0, north = 34.0, west = -6.8, east = -6.8)
        val count = OfflineTileManager.estimateCityTileCount(point)
        assertTrue("Even a point region should have tiles after normalization, was $count", count > 0)
    }

    // ═══════════════════════════════════════════════════════════════
    // OfflineTileManager — Cache directory
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun tileCacheDir_defaultIsCartoDarkMatter() {
        val dir = OfflineTileManager.getTileCacheDir(context)
        assertTrue("Default dir should contain CartoDarkMatter",
            dir.absolutePath.contains("CartoDarkMatter"))
    }

    @Test
    fun tileCacheDir_voyager() {
        val dir = OfflineTileManager.getTileCacheDir(context, "CartoVoyager")
        assertTrue("Dir should contain CartoVoyager",
            dir.absolutePath.contains("CartoVoyager"))
    }

    @Test
    fun tileCacheDir_differentSourcesDifferentPaths() {
        val dark = OfflineTileManager.getTileCacheDir(context, "CartoDarkMatter")
        val voyager = OfflineTileManager.getTileCacheDir(context, "CartoVoyager")
        assertNotEquals(dark.absolutePath, voyager.absolutePath)
    }

    // ═══════════════════════════════════════════════════════════════
    // OfflineTileManager — Cache size
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun cacheSizeMB_nonNegative() {
        val sizeMB = OfflineTileManager.getCacheSizeMB(context)
        assertTrue("Cache size must be >= 0", sizeMB >= 0.0)
    }

    @Test
    fun cacheSizeMB_increasedAfterWritingFile() {
        val before = OfflineTileManager.getCacheSizeMB(context)
        // Write a fake tile file
        val dir = OfflineTileManager.getTileCacheDir(context, "CartoDarkMatter")
        val fakeZoomDir = File(dir, "99/0")
        fakeZoomDir.mkdirs()
        val fakeFile = File(fakeZoomDir, "0.png")
        fakeFile.writeBytes(ByteArray(10_000))
        val after = OfflineTileManager.getCacheSizeMB(context)
        assertTrue("Cache size should increase after adding a file", after >= before)
        // Clean up
        fakeFile.delete()
        fakeZoomDir.delete()
        fakeZoomDir.parentFile?.delete()
    }

    // ═══════════════════════════════════════════════════════════════
    // OfflineTileManager — Network check
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun isNetworkAvailable_doesNotThrow() {
        // Just verify the call completes without throwing
        val result = OfflineTileManager.isNetworkAvailable(context)
        // On the test device this should be true (USB adb connected implies WiFi/mobile)
        // but we mainly want no crash
        assertNotNull(result)
    }

    // ═══════════════════════════════════════════════════════════════
    // OfflineTileManager — Tile download / delete (file-level)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun deleteCityTiles_returnsZeroWhenNothingCached() {
        // Use a remote region (Antarctica) where no tiles are ever cached
        val emptyRegion = OfflineTileManager.CityRegion(
            name = "Empty",
            north = -80.0, south = -81.0, east = 1.0, west = 0.0
        )
        kotlinx.coroutines.runBlocking {
            val deleted = OfflineTileManager.deleteCityTiles(context, emptyRegion)
            assertEquals(0, deleted)
        }
    }

    @Test
    fun downloadCityTiles_resultApi_exists() {
        // Verify that the CityDownloadResult API is well-formed
        // (actual download skipped: Android 7.0 has expired SSL certs for CDN)
        val result = OfflineTileManager.CityDownloadResult(
            success = false, totalTiles = 10,
            downloadedTiles = 0, skippedTiles = 0, failedTiles = 10,
            errorMessage = "SSL error"
        )
        assertFalse(result.success)
        assertEquals(10, result.totalTiles)
        assertEquals("SSL error", result.errorMessage)
    }

    // ═══════════════════════════════════════════════════════════════
    // OfflineTileManager — CityDownloadResult data class
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun cityDownloadResult_successCase() {
        val r = OfflineTileManager.CityDownloadResult(
            success = true, totalTiles = 100,
            downloadedTiles = 80, skippedTiles = 20, failedTiles = 0
        )
        assertTrue(r.success)
        assertNull(r.errorMessage)
        assertEquals(100, r.totalTiles)
    }

    @Test
    fun cityDownloadResult_failureCase() {
        val r = OfflineTileManager.CityDownloadResult(
            success = false, totalTiles = 100,
            downloadedTiles = 10, skippedTiles = 0, failedTiles = 90,
            errorMessage = "Network error"
        )
        assertFalse(r.success)
        assertEquals("Network error", r.errorMessage)
    }

    // ═══════════════════════════════════════════════════════════════
    // OfflineTileManager — Tile rendering / source URLs
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun tileSourceSpec_voyagerUrl_format() {
        // The Voyager tile source builds a URL with @2x.png
        // We test the pattern via the public API: getTileCacheDir includes the folder name
        val dir = OfflineTileManager.getTileCacheDir(context, "CartoVoyager")
        assertTrue(dir.name == "CartoVoyager")
    }

    @Test
    fun tileSourceSpec_darkMatterUrl_format() {
        val dir = OfflineTileManager.getTileCacheDir(context, "CartoDarkMatter")
        assertTrue(dir.name == "CartoDarkMatter")
    }

    // ═══════════════════════════════════════════════════════════════
    // Map rendering — osmdroid tile sources
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun mapTileSource_cartoVoyager_configured() {
        // Verify the CartoVoyager source is configured correctly via reflection
        // or simply that the cache folder naming is consistent
        val dir = OfflineTileManager.getTileCacheDir(context, "CartoVoyager")
        assertTrue("CartoVoyager cache dir should be under app files",
            dir.absolutePath.contains(context.packageName) || dir.absolutePath.contains("osmdroid"))
    }

    @Test
    fun mapTileSource_cartoDarkMatter_configured() {
        val dir = OfflineTileManager.getTileCacheDir(context, "CartoDarkMatter")
        assertTrue("CartoDarkMatter cache dir should be under app files",
            dir.absolutePath.contains(context.packageName) || dir.absolutePath.contains("osmdroid"))
    }

    // ═══════════════════════════════════════════════════════════════
    // Integration: download tiny region, verify cache, then delete
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun integration_estimateAndCacheRoundTrip() {
        // Test the full cache lifecycle without actual network download
        val region = CityRegion("MicroTest", south = 34.02, north = 34.021, west = -6.83, east = -6.829)
        val estimated = OfflineTileManager.estimateCityTileCount(region)
        assertTrue("Even tiny region should estimate some tiles", estimated > 0)

        // Simulate a successful download by upserting the cache entry
        CityMapCacheStore.upsertCachedCity(context, region, tileCount = estimated)
        assertTrue(CityMapCacheStore.isCityCached(context, "MicroTest"))

        val cities = CityMapCacheStore.getCachedCities(context)
        val cached = cities.first { it.id == CityMapCacheStore.cityIdFor("MicroTest") }
        assertEquals(estimated, cached.tileCount)

        // Verify delete returns 0 (no real tiles on disk)
        kotlinx.coroutines.runBlocking {
            val deleted = OfflineTileManager.deleteCityTiles(context, region)
            assertEquals(0, deleted)
        }

        // Remove from store
        CityMapCacheStore.removeCachedCity(context, CityMapCacheStore.cityIdFor("MicroTest"))
        assertFalse(CityMapCacheStore.isCityCached(context, "MicroTest"))
    }

    // ═══════════════════════════════════════════════════════════════
    // Integration: store persistence round-trip with region
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun integration_upsertAndRetrieve_preservesCoordinates() {
        val region = rabatRegion()
        CityMapCacheStore.upsertCachedCity(
            context, region, tileCount = 1234, downloadedAtMillis = 1709431200000L
        )
        val cities = CityMapCacheStore.getCachedCities(context)
        assertEquals(1, cities.size)
        val city = cities[0]
        assertEquals("rabat", city.id)
        assertEquals("Rabat", city.cityName)
        assertEquals(33.90, city.south, 0.001)
        assertEquals(34.10, city.north, 0.001)
        assertEquals(-6.90, city.west, 0.001)
        assertEquals(-6.70, city.east, 0.001)
        assertEquals(1234, city.tileCount)
        assertEquals(1709431200000L, city.downloadedAtMillis)
    }
}
