package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Weather from Open-Meteo: keyless, no account, and a compact JSON API that needs no scraping.
 *
 * This is the shape a weather question actually wants — one call, one place, current conditions
 * plus a short forecast — rather than the three fragile hops a skill had to take before this
 * existed (approval card, a search-results page, a regex over its markup). Open-Meteo's geocoding
 * is used so the model can say "Tokyo" or "Austin, TX" instead of looking up coordinates itself.
 *
 * Output is deliberately small and worded: WMO codes become conditions in words, because "light
 * drizzle" is signal a model can act on and "weather_code: 51" is a lookup it might not have.
 */
class WeatherTool : ToolHandler {
    override val definition = ToolDefinition(
        name = "get_weather",
        // Conditions are produced outside the device; treat them like any fetched content.
        returnsUntrustedContent = true,
        description = "Current conditions and a daily forecast for any place on Earth. Use this " +
            "for every weather or forecast question — never web_search or web_fetch.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "place":{"type":"string","description":"Place name, e.g. \"Tokyo\" or \"Austin, TX\"."},
               "days":{"type":"integer","description":"Forecast days including today. Default 1, max 7."},
               "units":{"type":"string","enum":["fahrenheit","celsius"],
                        "description":"Temperature unit. Default fahrenheit."}},
             "required":["place"],
             "additionalProperties":false}
        """.trimIndent(),
        readOnly = false,
        requiredPermissions = setOf("internet"),
    )

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext toolError("invalid_arguments", "Arguments were not valid JSON")
        val place = arguments.optString("place").trim()
        if (place.isEmpty()) return@withContext toolError("invalid_place", "A place name is required")
        if (place.length > MAX_PLACE_CHARS) {
            return@withContext toolError("invalid_place", "The place name is too long")
        }
        val days = arguments.optInt("days", 1).coerceIn(1, MAX_DAYS)
        val celsius = arguments.optString("units", "fahrenheit") == "celsius"

        val geocoded = runCatching { geocode(place) }.getOrElse { failure ->
            return@withContext toolError("geocode_failed", failure.message ?: failure::class.java.simpleName)
        } ?: return@withContext toolError(
            "unknown_place",
            "No place matched \"$place\". Try a nearby city, or add a country like \"Springfield, US\".",
        )

        runCatching { fetchForecast(geocoded, days, celsius) }
            .getOrElse { failure ->
                toolError("weather_failed", failure.message ?: failure::class.java.simpleName)
            }
    }

    private fun geocode(place: String): WeatherPlace? {
        val url = URL(
            "https://geocoding-api.open-meteo.com/v1/search?name=" +
                URLEncoder.encode(place, "UTF-8") + "&count=1&language=en&format=json",
        )
        return parseGeocode(fetchRaw(url, 20_000))
    }

    private fun fetchForecast(place: WeatherPlace, days: Int, celsius: Boolean): String {
        val unit = if (celsius) "celsius" else "fahrenheit"
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${place.latitude}&longitude=${place.longitude}" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&temperature_unit=$unit&wind_speed_unit=kmh&timezone=auto&forecast_days=$days",
        )
        return parseForecast(fetchRaw(url, 40_000), place, unit)
    }

    private companion object {
        const val MAX_DAYS = 7
        const val MAX_PLACE_CHARS = 96
    }
}

/** Reads Open-Meteo geocoding JSON into the one match the tool asked for, or null for no match. */
internal fun parseGeocode(json: String): WeatherPlace? {
    val root = JSONObject(json)
    val result = root.optJSONArray("results")?.optJSONObject(0) ?: return null
    val name = result.optString("name")
    if (name.isBlank()) return null
    return WeatherPlace(
        name = name,
        region = result.optString("admin1"),
        country = result.optString("country"),
        latitude = result.optDouble("latitude"),
        longitude = result.optDouble("longitude"),
    )
}

internal data class WeatherPlace(
    val name: String,
    val region: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
)

/** The label shown for the place: "Austin, Texas, US", collapsing missing parts. */
internal fun WeatherPlace.displayLabel(): String =
    listOf(name, region, country).filter { it.isNotBlank() }.joinToString(", ")

/**
 * Reduces an Open-Meteo forecast response to the compact object the model reads: current
 * conditions in words, and one line per forecast day. Parallel daily arrays are walked together
 * and stop at the shortest, so a truncated or odd response still yields the days that agree.
 */
internal fun parseForecast(json: String, place: WeatherPlace, unitName: String): String {
    val root = JSONObject(json)
    val current = root.optJSONObject("current") ?: throw java.io.IOException("Forecast had no current conditions")
    val daily = root.optJSONObject("daily") ?: throw java.io.IOException("Forecast had no daily section")

    val dates = daily.optJSONArray("time") ?: JSONArray()
    val codes = daily.optJSONArray("weather_code") ?: JSONArray()
    val highs = daily.optJSONArray("temperature_2m_max") ?: JSONArray()
    val lows = daily.optJSONArray("temperature_2m_min") ?: JSONArray()
    val precip = daily.optJSONArray("precipitation_probability_max") ?: JSONArray()

    val forecast = JSONArray()
    for (i in 0 until minOf(dates.length(), codes.length(), highs.length(), lows.length())) {
        forecast.put(
            JSONObject()
                .put("date", dates.optString(i))
                .put("condition", wmoCondition(codes.optInt(i)))
                .put("high", highs.optDouble(i))
                .put("low", lows.optDouble(i))
                .put("precipitation_chance_percent", precip.optInt(i, 0)),
        )
    }

    return JSONObject()
        .put("place", place.displayLabel())
        .put("units", unitName)
        .put("local_time", current.optString("time"))
        .put(
            "current",
            JSONObject()
                .put("temperature", current.optDouble("temperature_2m"))
                .put("feels_like", current.optDouble("apparent_temperature"))
                .put("humidity_percent", current.optDouble("relative_humidity_2m"))
                .put("wind_kmh", current.optDouble("wind_speed_10m"))
                .put("condition", wmoCondition(current.optInt("weather_code"))),
        )
        .put("forecast_days", forecast)
        .toString()
}

/** WMO 4677 weather codes, in words. Unknown codes degrade to a numbered description, never fail. */
internal fun wmoCondition(code: Int): String = when (code) {
    0 -> "clear sky"
    1 -> "mainly clear"
    2 -> "partly cloudy"
    3 -> "overcast"
    45 -> "fog"
    48 -> "freezing fog"
    51 -> "light drizzle"
    53 -> "moderate drizzle"
    55 -> "dense drizzle"
    56 -> "light freezing drizzle"
    57 -> "dense freezing drizzle"
    61 -> "slight rain"
    63 -> "moderate rain"
    65 -> "heavy rain"
    66 -> "light freezing rain"
    67 -> "heavy freezing rain"
    71 -> "slight snowfall"
    73 -> "moderate snowfall"
    75 -> "heavy snowfall"
    77 -> "snow grains"
    80 -> "slight rain showers"
    81 -> "moderate rain showers"
    82 -> "violent rain showers"
    85 -> "slight snow showers"
    86 -> "heavy snow showers"
    95 -> "thunderstorm"
    96 -> "thunderstorm with slight hail"
    99 -> "thunderstorm with heavy hail"
    else -> "weather code $code"
}
