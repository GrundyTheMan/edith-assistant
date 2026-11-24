package com.edith.app.tools.weather

import com.edith.app.core.net.HttpClients
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.settings.Settings
import com.edith.app.settings.Units
import com.edith.app.testing.FakeClock
import com.edith.app.tools.framework.PermissionNeed
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolResult
import com.edith.app.tools.location.Coordinates
import com.edith.app.tools.location.LocationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** C09: the real Open-Meteo fixtures (captured 2026-11-01), place resolution, codes, units, and the tool end to end. */
class WeatherToolTest {
    private val geocoding = MockWebServer()
    private val forecasts = MockWebServer()
    private val dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.IO, Dispatchers.Default)
    private lateinit var client: OpenMeteoClient

    @Before fun start() {
        geocoding.start()
        forecasts.start()
        client = OpenMeteoClient(lazyOf(HttpClients.over(OkHttpClient())), dispatchers, geocoding.url("/").toString(), forecasts.url("/").toString())
    }

    @After fun stop() {
        geocoding.close()
        forecasts.close()
    }

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("weather/$name")!!.readText()
    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()
    private fun ctx(units: Units = Units.Auto) = ToolContext(
        FakeClock(Instant.parse("2026-11-01T04:00:00Z"), ZoneId.of("America/Detroit")), Settings(units = units), dispatchers,
    )
    private fun tool(languageTag: String = "en-US") = WeatherTool(client) { languageTag }
    private fun args(json: String) = ToolArgs(Json.parseToJsonElement(json).jsonObject, tool().spec)

    // --- the captured responses parse ---------------------------------------------------------------------

    @Test fun `real geocoding and forecast responses parse`() = runTest {
        geocoding.enqueue(ok(fixture("geocode_fowlerville.json")))
        val places = (client.geocode("Fowlerville") as Outcome.Success).value
        val fowlerville = places.first()
        assertEquals("Fowlerville" to "Michigan", fowlerville.name to fowlerville.admin1)
        assertEquals("US", fowlerville.countryCode)
        assertEquals(42.66059, fowlerville.latitude, 0.00001)
        assertEquals("Fowlerville, Michigan (US)", fowlerville.label)

        geocoding.enqueue(ok(fixture("geocode_none.json")))   // no "results" key at all
        assertEquals(emptyList<Place>(), (client.geocode("Xqzzvillex") as Outcome.Success).value)

        forecasts.enqueue(ok(fixture("forecast_fowlerville_imperial.json")))
        val f = (client.forecast(42.66, -84.07, WeatherUnits.Imperial, days = 2) as Outcome.Success).value
        assertEquals(50.2, f.current.temperature!!, 0.001)
        assertEquals(45.5, f.current.feelsLike!!, 0.001)
        assertEquals(70, f.current.humidity)
        assertEquals(3, f.current.weatherCode)
        assertEquals(3, f.days.size)
        assertNotNull(f.days[1].high)
        assertEquals("2026-11-02", f.days[1].date)
    }

    @Test fun `requests ask for the chosen units and URL-encode the place`() = runTest {
        geocoding.enqueue(ok(fixture("geocode_none.json")))
        client.geocode("São Paulo & Co")
        val geo = geocoding.takeRequest().url
        assertEquals("São Paulo & Co", geo.queryParameter("name"))
        // The accent and the & are percent-encoded; a space is %20 or + (both mean a space in a query).
        assertTrue(geo.encodedQuery!!.contains(Regex("""name=S%C3%A3o(%20|\+)Paulo(%20|\+)%26(%20|\+)Co""")))

        forecasts.enqueue(ok(fixture("forecast_paris_metric.json")))
        client.forecast(48.85, 2.35, WeatherUnits.Metric, days = 0)
        val fc = forecasts.takeRequest().url
        assertEquals("celsius", fc.queryParameter("temperature_unit"))
        assertEquals("kmh", fc.queryParameter("wind_speed_unit"))
        assertEquals("1", fc.queryParameter("forecast_days"))
        assertEquals("auto", fc.queryParameter("timezone"))
    }

    @Test fun `units follow the setting, or the phone's country when automatic (D7)`() {
        assertEquals(WeatherUnits.Imperial, WeatherUnits.from(Units.Auto, "en-US"))
        assertEquals(WeatherUnits.Metric, WeatherUnits.from(Units.Auto, "en-GB"))
        assertEquals(WeatherUnits.Metric, WeatherUnits.from(Units.Auto, "en-CA"))
        assertEquals(WeatherUnits.Imperial, WeatherUnits.from(Units.Auto, "en-LR"))
        assertEquals(WeatherUnits.Metric, WeatherUnits.from(Units.Metric, "en-US"))
        assertEquals(WeatherUnits.Imperial, WeatherUnits.from(Units.Imperial, "fr-FR"))
    }

    // --- place resolution ---------------------------------------------------------------------------------

    private fun springfields() = run {
        listOf(
            Place("Springfield", 37.2, -93.3, "Missouri", "United States", "US", 170188),
            Place("Springfield", 39.8, -89.6, "Illinois", "United States", "US", 114394),
            Place("Springfield", 42.1, -72.6, "Massachusetts", "United States", "US", 154341),
        )
    }

    @Test fun `a state hint picks that place, abbreviations included`() {
        assertEquals(PlaceResolver.Query("Fowlerville", "MI"), PlaceResolver.parse(" Fowlerville , MI "))
        assertEquals(PlaceResolver.Query("Paris", "France"), PlaceResolver.parse("Paris, France"))
        assertEquals("Illinois", PlaceResolver.choose(springfields(), "IL")!!.place.admin1)
        assertEquals("Massachusetts", PlaceResolver.choose(springfields(), "massachusetts")!!.place.admin1)
        assertFalse(PlaceResolver.choose(springfields(), "IL")!!.ambiguous)
    }

    @Test fun `without a hint the most populous wins and the ambiguity is noted`() {
        val choice = PlaceResolver.choose(springfields(), null)!!
        assertEquals("Missouri", choice.place.admin1)
        assertTrue(choice.ambiguous)
        assertEquals(null, PlaceResolver.choose(emptyList(), null))
        assertFalse("a unique name isn't ambiguous", PlaceResolver.choose(springfields().take(1), null)!!.ambiguous)
    }

    @Test fun `every listed weather code has words`() {
        val listed = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)
        listed.forEach { assertNotNull("code $it", describeWeatherCode(it)) }
        assertEquals("partly cloudy", describeWeatherCode(2))
        assertEquals(null, describeWeatherCode(42))
    }

    // --- the tool end to end --------------------------------------------------------------------------------

    @Test fun `current weather in Fowlerville, MI`() = runTest {
        geocoding.enqueue(ok(fixture("geocode_fowlerville.json")))
        forecasts.enqueue(ok(fixture("forecast_fowlerville_imperial.json")))
        val result = tool().execute(args("""{"location":"Fowlerville, MI"}"""), ctx())
        assertEquals(ToolResult.Ok("Fowlerville, Michigan (US) — now 50°F (feels like 46°F), overcast, wind 6 mph, humidity 70%."), result)
        assertEquals("Fowlerville", geocoding.takeRequest().url.queryParameter("name"))
        assertEquals("fahrenheit", forecasts.takeRequest().url.queryParameter("temperature_unit"))
    }

    @Test fun `a forecast adds the next days, and stays short`() = runTest {
        geocoding.enqueue(ok(fixture("geocode_fowlerville.json")))
        forecasts.enqueue(ok(fixture("forecast_fowlerville_imperial.json")))
        val text = (tool().execute(args("""{"location":"Fowlerville","days":"2"}"""), ctx()) as ToolResult.Ok).text
        assertTrue(text, text.contains(" Tomorrow: high ") && text.contains("% chance of rain."))
        assertTrue(text, text.contains("Tuesday: "))   // 2026-11-03
        assertTrue("≤ 400 chars, was ${text.length}", text.length <= 400)
    }

    @Test fun `metric for a metric locale`() = runTest {
        geocoding.enqueue(ok("""{"results":[{"name":"Paris","latitude":48.85341,"longitude":2.3488,"country_code":"FR","country":"France","admin1":"Île-de-France","population":2138551}]}"""))
        forecasts.enqueue(ok(fixture("forecast_paris_metric.json")))
        val text = (tool("fr-FR").execute(args("""{"place":"Paris, France"}"""), ctx()) as ToolResult.Ok).text
        assertTrue(text, text.startsWith("Paris, Île-de-France (FR) — now ") && text.contains("°C") && text.contains("km/h"))
    }

    @Test fun `ambiguous, unknown, here, and service failures give plain text`() = runTest {
        geocoding.enqueue(ok(fixture("geocode_springfield.json")))
        forecasts.enqueue(ok(fixture("forecast_fowlerville_imperial.json")))
        val spring = (tool().execute(args("""{"location":"Springfield"}"""), ctx()) as ToolResult.Ok).text
        assertTrue(spring, spring.startsWith("Springfield, Missouri (US) (other places share this name"))

        geocoding.enqueue(ok(fixture("geocode_none.json")))
        assertEquals(ToolResult.Error("Couldn't find a place called Xqzzvillex."), tool().execute(args("""{"location":"Xqzzvillex"}"""), ctx()))

        assertEquals(ToolResult.Error("I need your location — ask the user for a city."), tool().execute(args("""{"location":"here"}"""), ctx()))

        geocoding.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(ToolResult.Error("The weather service didn't respond — try again shortly."), tool().execute(args("""{"location":"Boston"}"""), ctx()))

        geocoding.enqueue(ok("this is not json"))
        assertEquals(ToolResult.Error("The weather service didn't respond — try again shortly."), tool().execute(args("""{"location":"Boston"}"""), ctx()))

        geocoding.close()   // nothing listening: like no network
        assertEquals(ToolResult.Error("The weather service didn't respond — try again shortly."), tool().execute(args("""{"location":"Boston"}"""), ctx()))

        assertEquals("no place and no device location", ToolResult.Error("I need your location — ask the user for a city."), tool().execute(args("""{}"""), ctx()))
        assertNull("nothing to ask for without a provider", tool().permission(args("""{"location":"here"}""")))
    }

    // --- C11: "here" ------------------------------------------------------------------------------------

    private class FakeLocation(
        private val at: Outcome<Coordinates> = Outcome.Success(Coordinates(42.66, -84.07)),
        private val name: String? = "Fowlerville, Michigan (US)",
    ) : LocationProvider {
        var asked = 0
        override suspend fun current(): Outcome<Coordinates> { asked++; return at }
        override suspend fun placeName(at: Coordinates) = name
    }

    @Test fun `here uses the phone's location, named on the phone`() = runTest {
        val device = FakeLocation()
        val tool = WeatherTool(client, device) { "en-US" }
        forecasts.enqueue(ok(fixture("forecast_fowlerville_imperial.json")))
        assertEquals(ToolResult.Ok("Fowlerville, Michigan (US) — now 50°F (feels like 46°F), overcast, wind 6 mph, humidity 70%."),
            tool.execute(args("""{"location":"here"}"""), ctx()))
        val request = forecasts.takeRequest().url
        assertEquals("42.66" to "-84.07", request.queryParameter("latitude") to request.queryParameter("longitude"))
        assertEquals("no geocoding for here", 0, geocoding.requestCount)

        listOf("""{"location":"here"}""", """{"location":"Near me."}""", """{"location":"current location"}""", """{"location":""}""", "{}")
            .forEach { assertEquals(it, PermissionNeed(LocationProvider.PERMISSION, LocationProvider.DENIED), tool.permission(args(it))) }
        assertNull("a named place needs no permission", tool.permission(args("""{"location":"Boston"}""")))
        assertTrue(tool.guidance!!.contains("location \"here\""))
    }

    @Test fun `here without a place name, and location failures, give plain text`() = runTest {
        forecasts.enqueue(ok(fixture("forecast_fowlerville_imperial.json")))
        val unnamed = (WeatherTool(client, FakeLocation(name = null)) { "en-US" }.execute(args("{}"), ctx()) as ToolResult.Ok).text
        assertTrue(unnamed, unnamed.startsWith("Your current location — now 50°F"))
        assertFalse("coordinates never reach the model", unnamed.contains("42.66"))

        val off = FakeLocation(at = Outcome.Failure(AppError.ToolFailed("location", LocationProvider.LOCATION_OFF)))
        assertEquals(ToolResult.Error(LocationProvider.LOCATION_OFF), WeatherTool(client, off) { "en-US" }.execute(args("""{"location":"here"}"""), ctx()))
        assertEquals("no forecast without a location", 1, forecasts.requestCount)
    }

    @Test fun `coordinates are rounded to about a kilometre`() {
        assertEquals(Coordinates(42.66, -84.07), Coordinates(42.660594, -84.0733).rounded())
        assertEquals("Fowlerville, Michigan (US)", com.edith.app.tools.location.AndroidLocationProvider.label("Fowlerville", "Michigan", "US"))
        assertEquals("Michigan (US)", com.edith.app.tools.location.AndroidLocationProvider.label(null, "Michigan", "US"))
        assertNull(com.edith.app.tools.location.AndroidLocationProvider.label(null, null, "US"))
    }
}
