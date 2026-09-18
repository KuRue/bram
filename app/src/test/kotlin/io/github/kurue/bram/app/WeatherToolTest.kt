package io.github.kurue.bram.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherToolTest {

    @Test
    fun `parseGeocode reads the first match with its place parts`() {
        val geocoded = parseGeocode(
            """
            {"results":[{"id":1850147,"name":"Tokyo","latitude":35.6895,"longitude":139.69171,
             "country":"Japan","admin1":"Tokyo"}],"generationtime_ms":0.9}
            """.trimIndent(),
        )
        assertEquals("Tokyo", geocoded?.name)
        assertEquals("Japan", geocoded?.country)
        assertEquals(35.6895, geocoded?.latitude ?: 0.0, 0.0001)
    }

    @Test
    fun `parseGeocode is null when nothing matched`() {
        assertNull(parseGeocode("""{"generationtime_ms":0.4}"""))
        assertNull(parseGeocode("""{"results":[]}"""))
    }

    @Test
    fun `displayLabel collapses the missing parts`() {
        val austin = WeatherPlace("Austin", "Texas", "United States", 30.27, -97.74)
        assertEquals("Austin, Texas, United States", austin.displayLabel())
        assertEquals("Tokyo, Japan", WeatherPlace("Tokyo", "", "Japan", 0.0, 0.0).displayLabel())
        assertEquals("Eiffel Tower", WeatherPlace("Eiffel Tower", "", "", 0.0, 0.0).displayLabel())
    }

    @Test
    fun `parseForecast words the conditions and walks the parallel daily arrays`() {
        val place = WeatherPlace("Tokyo", "", "Japan", 35.68, 139.69)
        val json = """
            {"latitude":35.7,"longitude":139.6875,
             "current":{"time":"2026-08-16T11:00","temperature_2m":74.5,"apparent_temperature":82.1,
              "relative_humidity_2m":89,"weather_code":51,"wind_speed_10m":3.7},
             "daily":{"time":["2026-08-16","2026-08-17","2026-08-18"],
              "weather_code":[51,3,95],
              "temperature_2m_max":[78.1,83.5,87.8],
              "temperature_2m_min":[71.0,70.7,72.4],
              "precipitation_probability_max":[86,69,38]}}
        """.trimIndent()

        val parsed = JSONObject(parseForecast(json, place, "fahrenheit"))
        assertEquals("Tokyo, Japan", parsed.getString("place"))
        assertEquals("light drizzle", parsed.getJSONObject("current").getString("condition"))
        assertEquals(74.5, parsed.getJSONObject("current").getDouble("temperature"), 0.01)
        val days = parsed.getJSONArray("forecast_days")
        assertEquals(3, days.length())
        assertEquals("overcast", days.getJSONObject(1).getString("condition"))
        assertEquals("thunderstorm", days.getJSONObject(2).getString("condition"))
        assertEquals(86, days.getJSONObject(0).getInt("precipitation_chance_percent"))
    }

    @Test
    fun `parseForecast stops at the shortest daily array`() {
        val place = WeatherPlace("X", "", "", 0.0, 0.0)
        val json = """
            {"current":{"time":"t","temperature_2m":1,"apparent_temperature":1,"relative_humidity_2m":1,
              "weather_code":0,"wind_speed_10m":1},
             "daily":{"time":["2026-08-16","2026-08-17"],
              "weather_code":[1,2,3],
              "temperature_2m_max":[10,20,30],
              "temperature_2m_min":[5,15,25],
              "precipitation_probability_max":[0,0,0]}}
        """.trimIndent()
        assertEquals(2, JSONObject(parseForecast(json, place, "celsius")).getJSONArray("forecast_days").length())
    }

    @Test
    fun `parseForecast throws rather than inventing weather when sections are missing`() {
        val place = WeatherPlace("X", "", "", 0.0, 0.0)
        assertTrue(runCatching { parseForecast("""{"current":{}}""", place, "fahrenheit") }.isFailure)
        assertTrue(runCatching { parseForecast("""{"daily":{}}""", place, "fahrenheit") }.isFailure)
    }

    @Test
    fun `wmoCondition covers the codes Open-Meteo sends and degrades for unknown ones`() {
        assertEquals("clear sky", wmoCondition(0))
        assertEquals("heavy rain", wmoCondition(65))
        assertEquals("thunderstorm with slight hail", wmoCondition(96))
        assertEquals("weather code 42", wmoCondition(42))
    }

    @Test
    fun `get_weather definition needs the internet permission and is not read-only`() {
        val def = WeatherTool().definition
        assertEquals("get_weather", def.name)
        assertTrue(def.requiredPermissions.contains("internet"))
        assertTrue(!def.readOnly)
        assertTrue(def.returnsUntrustedContent)
        assertTrue(def.approvalScopeKeys.isEmpty())
    }
}
