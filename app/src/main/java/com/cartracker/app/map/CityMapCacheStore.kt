package com.cartracker.app.map

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Persistence for city-level offline map downloads and city prompt cooldowns.
 */
data class CachedCityMap(
    val id: String,
    val cityName: String,
    val south: Double,
    val north: Double,
    val west: Double,
    val east: Double,
    val downloadedAtMillis: Long,
    val tileCount: Int
) {
    fun toRegion(): OfflineTileManager.CityRegion {
        return OfflineTileManager.CityRegion(
            name = cityName,
            south = south,
            north = north,
            west = west,
            east = east
        )
    }
}

object CityMapCacheStore {
    private const val PREFS_NAME = "city_map_cache_store"
    private const val KEY_CITIES = "cached_city_maps"
    private const val KEY_PROMPT_COOLDOWNS = "city_prompt_cooldowns"

    private const val PROMPT_COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

    fun getCachedCities(context: Context): List<CachedCityMap> {
        val raw = prefs(context).getString(KEY_CITIES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    val cityName = item.optString("cityName")
                    if (id.isBlank() || cityName.isBlank()) continue
                    add(
                        CachedCityMap(
                            id = id,
                            cityName = cityName,
                            south = item.optDouble("south"),
                            north = item.optDouble("north"),
                            west = item.optDouble("west"),
                            east = item.optDouble("east"),
                            downloadedAtMillis = item.optLong("downloadedAtMillis"),
                            tileCount = item.optInt("tileCount")
                        )
                    )
                }
            }.sortedBy { it.cityName.lowercase(Locale.ROOT) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun upsertCachedCity(
        context: Context,
        region: OfflineTileManager.CityRegion,
        tileCount: Int,
        downloadedAtMillis: Long = System.currentTimeMillis()
    ) {
        val cityId = cityIdFor(region.name)
        val updated = getCachedCities(context)
            .filterNot { it.id == cityId }
            .toMutableList()
            .apply {
                add(
                    CachedCityMap(
                        id = cityId,
                        cityName = region.name,
                        south = region.south,
                        north = region.north,
                        west = region.west,
                        east = region.east,
                        downloadedAtMillis = downloadedAtMillis,
                        tileCount = tileCount
                    )
                )
            }
            .sortedBy { it.cityName.lowercase(Locale.ROOT) }

        persistCities(context, updated)
        clearPromptCooldown(context, region.name)
    }

    fun removeCachedCity(context: Context, cityId: String): CachedCityMap? {
        val existing = getCachedCities(context)
        val removed = existing.firstOrNull { it.id == cityId } ?: return null
        persistCities(context, existing.filterNot { it.id == cityId })
        return removed
    }

    fun clearCachedCities(context: Context) {
        persistCities(context, emptyList())
    }

    fun isCityCached(context: Context, cityName: String): Boolean {
        val cityId = cityIdFor(cityName)
        return getCachedCities(context).any { it.id == cityId }
    }

    fun canPromptForCity(context: Context, cityName: String): Boolean {
        if (isCityCached(context, cityName)) return false
        val cooldowns = getPromptCooldowns(context)
        val until = cooldowns[cityIdFor(cityName)] ?: 0L
        return System.currentTimeMillis() >= until
    }

    fun deferPrompt(context: Context, cityName: String) {
        val cityId = cityIdFor(cityName)
        val cooldowns = getPromptCooldowns(context).toMutableMap()
        cooldowns[cityId] = System.currentTimeMillis() + PROMPT_COOLDOWN_MS
        persistPromptCooldowns(context, cooldowns)
    }

    fun clearPromptCooldown(context: Context, cityName: String) {
        val cityId = cityIdFor(cityName)
        val cooldowns = getPromptCooldowns(context).toMutableMap()
        cooldowns.remove(cityId)
        persistPromptCooldowns(context, cooldowns)
    }

    fun cityIdFor(cityName: String): String {
        return cityName
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "city" }
    }

    private fun persistCities(context: Context, cities: List<CachedCityMap>) {
        val arr = JSONArray()
        cities.forEach { city ->
            arr.put(
                JSONObject()
                    .put("id", city.id)
                    .put("cityName", city.cityName)
                    .put("south", city.south)
                    .put("north", city.north)
                    .put("west", city.west)
                    .put("east", city.east)
                    .put("downloadedAtMillis", city.downloadedAtMillis)
                    .put("tileCount", city.tileCount)
            )
        }
        prefs(context).edit().putString(KEY_CITIES, arr.toString()).apply()
    }

    private fun getPromptCooldowns(context: Context): Map<String, Long> {
        val raw = prefs(context).getString(KEY_PROMPT_COOLDOWNS, "{}") ?: "{}"
        return try {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { key ->
                    put(key, json.optLong(key, 0L))
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persistPromptCooldowns(context: Context, cooldowns: Map<String, Long>) {
        val json = JSONObject()
        cooldowns.forEach { (cityId, until) ->
            json.put(cityId, until)
        }
        prefs(context).edit().putString(KEY_PROMPT_COOLDOWNS, json.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
