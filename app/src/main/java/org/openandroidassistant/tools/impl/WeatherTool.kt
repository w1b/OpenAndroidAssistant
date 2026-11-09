/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools.impl

import com.google.gson.JsonObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import org.openandroidassistant.data.settings.effectiveUnits
import org.openandroidassistant.orchestrator.RenderCard
import org.openandroidassistant.permissions.AppCapability
import org.openandroidassistant.proto.UnitSystem
import org.openandroidassistant.tools.BaseTool
import org.openandroidassistant.tools.HandleType
import org.openandroidassistant.tools.ParamKind
import org.openandroidassistant.tools.ResultItem
import org.openandroidassistant.tools.ToolCategory
import org.openandroidassistant.tools.ToolContext
import org.openandroidassistant.tools.ToolEnvironment
import org.openandroidassistant.tools.ToolMetadata
import org.openandroidassistant.tools.ToolOutcome
import org.openandroidassistant.tools.ToolParam
import org.openandroidassistant.tools.ToolSchema
import org.openandroidassistant.tools.ValidatedCall

private val HOUR_LABEL = DateTimeFormatter.ofPattern("h a", Locale.US)

/** Weather from Open-Meteo (no key). The tool geocodes; the model only names a place. */
class WeatherTool @Inject constructor() : BaseTool() {

  override val schema = schemaWith(locationRequired = false)

  override fun schema(env: ToolEnvironment): ToolSchema = schemaWith(locationRequired = !env.locationAvailable)

  private fun schemaWith(locationRequired: Boolean) =
    ToolSchema(
      name = "get_weather",
      description =
        "Get the weather: current conditions, the next hours, and the daily forecast for up to a week." +
          if (locationRequired) "" else " Omit location for where the user is.",
      params =
        listOf(
          ToolParam(
            name = "location",
            type = ParamKind.STRING,
            description = "City or postal code, optionally with region or country.",
            required = locationRequired,
          ),
          ToolParam(
            name = "date",
            type = ParamKind.STRING,
            description = "A day to focus on, YYYY-MM-DD. Defaults to today.",
            required = false,
          ),
        ),
    )

  override val metadata =
    ToolMetadata(
      id = "get_weather",
      title = "Weather",
      summary = "Current conditions and forecasts from Open-Meteo.",
      category = ToolCategory.INFORMATION,
      allowedWhenLockedDefault = true,
      optionalCapabilities = listOf(AppCapability.LOCATION),
      progressPhrase = "Checking the weather.",
    )

  private data class Place(val name: String, val lat: Double, val lon: Double)

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val requested = call.stringArg("location")
    val place =
      if (requested != null) {
        geocode(requested) ?: return ToolOutcome.Steering("not_found", "No place matched \"$requested\". Ask for a city and region or a postal code.")
      } else {
        val here = context.location.current()
          ?: return ToolOutcome.Steering("needs_location", "The user's location isn't available. Ask which city they mean.")
        Place(here.label ?: "your area", here.latitude, here.longitude)
      }
    val imperial = context.settings.effectiveUnits == UnitSystem.UNITS_IMPERIAL
    val focus = call.stringArg("date")?.let(::parseLocalDate)
    val json = httpGetJson(forecastUrl(place, imperial)).asJsonObject
    return ToolOutcome.Success(items = listOf(item(place, json, imperial, focus)))
  }

  private fun forecastUrl(place: Place, imperial: Boolean): String =
    "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
      "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
      "&hourly=temperature_2m,weather_code,precipitation_probability" +
      "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code,sunrise,sunset" +
      "&timezone=auto&forecast_days=7" +
      (if (imperial) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else "")

  private fun item(place: Place, json: JsonObject, imperial: Boolean, focus: LocalDate?): ResultItem {
    val unit = if (imperial) "°F" else "°C"
    val current = json.getAsJsonObject("current")
    val hourly = json.getAsJsonObject("hourly")
    val daily = json.getAsJsonObject("daily")
    val nowTime = LocalDateTime.parse(current["time"].asString)
    val today = nowTime.toLocalDate()
    val day = focus?.takeIf { !it.isBefore(today) } ?: today

    val times = hourly.getAsJsonArray("time").map { LocalDateTime.parse(it.asString) }
    val temps = hourly.getAsJsonArray("temperature_2m")
    val codes = hourly.getAsJsonArray("weather_code")
    val precip = hourly.getAsJsonArray("precipitation_probability")
    val hourIndexes =
      times.indices.filter { i ->
        val t = times[i]
        t.toLocalDate() == day && (day != today || !t.isBefore(nowTime.withMinute(0)))
      }
    val hours =
      hourIndexes.map { i ->
        RenderCard.HourForecast(
          label = HOUR_LABEL.format(times[i]),
          temperature = "${temps[i].asDouble.roundToInt()}°",
          summary = weatherCodeText(codes[i].asInt),
          precipitationChance = precip[i].takeUnless { it.isJsonNull }?.asInt?.let { "$it%" },
        )
      }

    val days = daily.getAsJsonArray("time").map { LocalDate.parse(it.asString) }
    val d = days.indexOf(day).coerceAtLeast(0)
    val high = daily.getAsJsonArray("temperature_2m_max")[d].asDouble.roundToInt()
    val low = daily.getAsJsonArray("temperature_2m_min")[d].asDouble.roundToInt()
    val dayPrecip = daily.getAsJsonArray("precipitation_probability_max")[d].takeUnless { it.isJsonNull }?.asInt
    val temp = current["temperature_2m"].asDouble.roundToInt()
    val summary = weatherCodeText(current["weather_code"].asInt)

    val projection =
      buildMap<String, Any?> {
        put("place", place.name)
        put("units", unit)
        if (day == today) {
          put("now", "$temp$unit, $summary, feels like ${current["apparent_temperature"].asDouble.roundToInt()}$unit")
        }
        put("day", "${formatDay(day)}: high $high$unit, low $low$unit, ${weatherCodeText(daily.getAsJsonArray("weather_code")[d].asInt)}, " +
          "${dayPrecip ?: 0}% chance of precipitation")
        put("hourly", hours.joinToString("; ") { "${it.label} ${it.temperature} ${it.summary} ${it.precipitationChance ?: ""}".trim() })
        put(
          "week",
          days.indices.drop(1).joinToString("; ") { i ->
            "${formatDay(days[i])} ${daily.getAsJsonArray("temperature_2m_max")[i].asDouble.roundToInt()}/" +
              "${daily.getAsJsonArray("temperature_2m_min")[i].asDouble.roundToInt()} " +
              weatherCodeText(daily.getAsJsonArray("weather_code")[i].asInt)
          },
        )
      }
    return ResultItem(
      type = HandleType.WEATHER,
      nativeId = "${place.lat},${place.lon},$day",
      projection = projection,
      renderPayload =
        RenderCard.Weather(
          place = place.name,
          summary = if (day == today) summary else weatherCodeText(daily.getAsJsonArray("weather_code")[d].asInt),
          temperature = if (day == today) "$temp°" else "$high°",
          high = "$high°",
          low = "$low°",
          precipitationChance = dayPrecip?.let { "$it%" },
          hours = hours,
        ),
    )
  }

  /** Open-Meteo's geocoder matches one place name, so try shorter leading fragments too. */
  private suspend fun geocode(query: String): Place? {
    for (candidate in candidateNames(query)) {
      val url = "https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(candidate, "UTF-8")
      val results = runCatching { httpGetJson(url).asJsonObject.getAsJsonArray("results") }.getOrNull() ?: continue
      if (results.isEmpty) continue
      val first = results[0].asJsonObject
      val name = buildString {
        append(first["name"].asString)
        val region = first["admin1"]?.takeIf { !it.isJsonNull }?.asString
        val country = first["country"]?.takeIf { !it.isJsonNull }?.asString
        (region ?: country)?.let { append(", $it") }
      }
      return Place(name, first["latitude"].asDouble, first["longitude"].asDouble)
    }
    return null
  }

  private fun candidateNames(query: String): List<String> {
    val trimmed = query.trim()
    val beforeComma = trimmed.substringBefore(',').trim()
    val words = beforeComma.split(Regex("\\s+"))
    val fragments = (words.size downTo 1).map { words.take(it).joinToString(" ") }
    return (listOf(trimmed, beforeComma) + fragments).distinct().filter { it.isNotBlank() }
  }
}

internal fun weatherCodeText(code: Int): String =
  when (code) {
    0 -> "clear"
    1 -> "mostly clear"
    2 -> "partly cloudy"
    3 -> "cloudy"
    45, 48 -> "foggy"
    51, 53, 55, 56, 57 -> "drizzle"
    61, 63, 66 -> "rain"
    65, 67 -> "heavy rain"
    71, 73, 77 -> "snow"
    75 -> "heavy snow"
    80, 81 -> "rain showers"
    82 -> "heavy showers"
    85, 86 -> "snow showers"
    95, 96, 99 -> "thunderstorms"
    else -> "unsettled"
  }
