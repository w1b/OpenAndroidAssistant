/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.JsonObject
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.openandroidassistant.data.settings.effectiveNominatimUrl
import org.openandroidassistant.data.settings.Secret
import org.openandroidassistant.data.settings.SecretStore
import org.openandroidassistant.data.settings.effectivePlacesProvider
import org.openandroidassistant.data.settings.effectiveUnits
import org.openandroidassistant.orchestrator.RenderCard
import org.openandroidassistant.permissions.AppCapability
import org.openandroidassistant.proto.PlacesProvider
import org.openandroidassistant.proto.UnitSystem
import org.openandroidassistant.tools.ApprovalContent
import org.openandroidassistant.tools.BaseTool
import org.openandroidassistant.tools.Handoff
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
import org.openandroidassistant.tools.UserLocation
import org.openandroidassistant.tools.ValidatedCall

private const val MAX_PLACES = 6

/** Everyday words and the OpenStreetMap category Nominatim understands. */
private val OSM_CATEGORIES =
  listOf(
    listOf("coffee", "espresso", "latte") to "cafe",
    listOf("gas", "petrol", "fuel") to "fuel",
    listOf("grocery", "groceries", "supermarket") to "supermarket",
    listOf("drugstore", "drug store", "chemist") to "pharmacy",
    listOf("food", "restaurant", "dinner", "lunch", "eat") to "restaurant",
    listOf("burger", "fast food") to "fast food",
    listOf("bar", "pub", "beer") to "pub",
    listOf("atm", "cash machine") to "atm",
    listOf("hotel", "motel") to "hotel",
    listOf("doctor", "clinic", "urgent care") to "clinic",
    listOf("hospital", "emergency room") to "hospital",
    listOf("parking", "car park") to "parking",
    listOf("charging", "charger", "ev charging") to "charging station",
    listOf("gym", "fitness") to "fitness centre",
    listOf("library") to "library",
    listOf("post office", "mail") to "post office",
  )

/** A place as returned by a provider, before it becomes a result item. */
internal data class FoundPlace(
  val id: String,
  val name: String,
  val category: String?,
  val address: String?,
  val lat: Double,
  val lon: Double,
  val phone: String?,
  val hours: String?,
  val openNow: Boolean?,
)

internal fun distanceKm(a: UserLocation, lat: Double, lon: Double): Double {
  val r = 6371.0
  val dLat = Math.toRadians(lat - a.latitude)
  val dLon = Math.toRadians(lon - a.longitude)
  val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(lat)) * sin(dLon / 2) * sin(dLon / 2)
  return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}

internal fun distanceLabel(km: Double, imperial: Boolean): String =
  if (imperial) "%.1f mi".format(Locale.US, km * 0.621371) else if (km < 1) "${(km * 1000).toInt()} m" else "%.1f km".format(Locale.US, km)

/** Find businesses and places: nearby by default, or near a named area. */
class FindPlacesTool @Inject constructor(private val secrets: SecretStore) : BaseTool() {

  override val schema = schemaWith(locationRequired = false)

  override fun schema(env: ToolEnvironment): ToolSchema = schemaWith(!env.locationAvailable)

  private fun schemaWith(locationRequired: Boolean) =
    ToolSchema(
      name = "find_places",
      description =
        "Find places and businesses (\"coffee\", \"pharmacy\", \"Blue Bottle\"). Returns address, " +
          "phone, opening hours and distance. " +
          if (locationRequired) "Pass near, since the user's location isn't available." else "Searches near the user unless near is given.",
      params =
        listOf(
          ToolParam("query", ParamKind.STRING, "A business name, or a kind of place like cafe, pharmacy, supermarket, fuel or restaurant."),
          ToolParam("near", ParamKind.STRING, "A city, neighborhood or address to search around.", required = locationRequired),
        ),
    )

  override val metadata =
    ToolMetadata(
      id = "find_places",
      title = "Find places",
      summary = "Search for businesses and places, with hours and phone numbers.",
      category = ToolCategory.INFORMATION,
      allowedWhenLockedDefault = true,
      optionalCapabilities = listOf(AppCapability.LOCATION),
      progressPhrase = "Looking that up.",
    )

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val query = call.stringArg("query") ?: return ToolOutcome.Steering("error", "What kind of place?")
    val near = call.stringArg("near")
    val origin =
      if (near != null) geocode(near, context.settings.effectiveNominatimUrl) ?: return ToolOutcome.Steering("not_found", "Couldn't find \"$near\". Ask for a clearer place.")
      else context.location.current() ?: return ToolOutcome.Steering("needs_location", "The user's location isn't available. Ask where to search.")
    val google = context.settings.effectivePlacesProvider == PlacesProvider.PLACES_GOOGLE
    val places =
      if (google) {
        val key = secrets.get(Secret.GOOGLE_PLACES_KEY)
          ?: return ToolOutcome.Steering("error", "Google Places is selected but has no API key. Tell the user to add it in settings.")
        searchGoogle(key, query, origin)
      } else {
        searchOsm(query, origin, context.settings.effectiveNominatimUrl)
      }
    if (places.isEmpty()) return ToolOutcome.Steering("empty", "Nothing matched \"$query\" nearby. Tell the user, or try other words.")
    val imperial = context.settings.effectiveUnits == UnitSystem.UNITS_IMPERIAL
    val items =
      places
        .sortedBy { distanceKm(origin, it.lat, it.lon) }
        .take(MAX_PLACES)
        .map { p ->
          val distance = distanceLabel(distanceKm(origin, p.lat, p.lon), imperial)
          ResultItem(
            type = HandleType.PLACE,
            nativeId = p.id,
            projection =
              mapOf(
                "name" to p.name,
                "category" to p.category,
                "address" to p.address,
                "distance" to distance,
                "phone" to p.phone,
                "hours" to p.hours,
                "open_now" to p.openNow,
              ),
            renderPayload =
              PlacePayload(
                card = RenderCard.Place(p.name, p.address, distance, p.hours ?: p.openNow?.let { if (it) "Open now" else "Closed now" }, p.phone, p.category),
                lat = p.lat,
                lon = p.lon,
              ),
          )
        }
    return ToolOutcome.Success(items = items)
  }

  private suspend fun geocode(text: String, nominatim: String): UserLocation? =
    runCatching {
        val arr = httpGetJson("$nominatim/search?format=jsonv2&limit=1&q=" + URLEncoder.encode(text, "UTF-8")).asJsonArray
        if (arr.isEmpty) null
        else arr[0].asJsonObject.let { UserLocation(it["lat"].asString.toDouble(), it["lon"].asString.toDouble(), it["display_name"]?.asString) }
      }
      .getOrNull()

  /**
   * Nominatim search bounded to a box around [origin]; its extratags carry hours and phone.
   * Nominatim matches OpenStreetMap category words ("cafe", "fuel"), so everyday words are tried
   * as their category too.
   */
  private suspend fun searchOsm(query: String, origin: UserLocation, nominatim: String): List<FoundPlace> {
    val terms = listOf(query) + osmCategory(query).filter { it != query.lowercase() }
    for (term in terms) {
      val found = searchOsmTerm(term, origin, nominatim)
      if (found.isNotEmpty()) return found
    }
    return emptyList()
  }

  private suspend fun searchOsmTerm(query: String, origin: UserLocation, nominatim: String): List<FoundPlace> {
    for (radiusDeg in listOf(0.05, 0.25)) {
      val box = "${origin.longitude - radiusDeg},${origin.latitude + radiusDeg},${origin.longitude + radiusDeg},${origin.latitude - radiusDeg}"
      val url =
        "$nominatim/search?format=jsonv2&limit=15&extratags=1&addressdetails=1&bounded=1&viewbox=$box&q=" +
          URLEncoder.encode(query, "UTF-8")
      val arr = runCatching { httpGetJson(url).asJsonArray }.getOrNull() ?: continue
      val found =
        arr.mapNotNull { el ->
          val o = el.asJsonObject
          val tags = o.obj("extratags")
          val address = o.obj("address")
          val name = o["name"]?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
          FoundPlace(
            id = "osm:${o["osm_type"]?.asString}:${o["osm_id"]?.asString}",
            name = name,
            category = o["type"]?.asString?.replace('_', ' '),
            address = address?.let(::shortAddress),
            lat = o["lat"].asString.toDouble(),
            lon = o["lon"].asString.toDouble(),
            phone = tags?.str("phone") ?: tags?.str("contact:phone"),
            hours = tags?.str("opening_hours"),
            openNow = null,
          )
        }
      if (found.isNotEmpty()) return found
    }
    return emptyList()
  }

  private fun osmCategory(query: String): List<String> {
    val q = query.lowercase().trim()
    return OSM_CATEGORIES.filter { (words, _) -> words.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(q) } }.map { it.second }
  }

  private fun shortAddress(a: JsonObject): String? {
    val street = listOfNotNull(a.str("house_number"), a.str("road")).joinToString(" ").ifEmpty { null }
    val city = a.str("city") ?: a.str("town") ?: a.str("village") ?: a.str("suburb")
    return listOfNotNull(street, city).joinToString(", ").ifEmpty { null }
  }

  private suspend fun searchGoogle(key: String, query: String, origin: UserLocation): List<FoundPlace> {
    val body =
      JsonObject().apply {
        addProperty("textQuery", query)
        add(
          "locationBias",
          JsonObject().apply {
            add(
              "circle",
              JsonObject().apply {
                add("center", JsonObject().apply { addProperty("latitude", origin.latitude); addProperty("longitude", origin.longitude) })
                addProperty("radius", 10_000.0)
              },
            )
          },
        )
        addProperty("pageSize", 10)
      }
    val json =
      httpPostJson(
        "https://places.googleapis.com/v1/places:searchText",
        body.toString(),
        mapOf(
          "X-Goog-Api-Key" to key,
          "X-Goog-FieldMask" to
            "places.id,places.displayName,places.shortFormattedAddress,places.location,places.nationalPhoneNumber," +
              "places.currentOpeningHours.openNow,places.regularOpeningHours.weekdayDescriptions,places.primaryTypeDisplayName",
        ),
      ).asJsonObject
    return json.arr("places")?.mapNotNull { el ->
      val p = el.asJsonObject
      val location = p.obj("location") ?: return@mapNotNull null
      val hours = p.obj("regularOpeningHours")?.arr("weekdayDescriptions")?.joinToString("; ") { it.asString }
      FoundPlace(
        id = "g:${p.str("id")}",
        name = p.obj("displayName")?.str("text") ?: "Place",
        category = p.obj("primaryTypeDisplayName")?.str("text"),
        address = p.str("shortFormattedAddress"),
        lat = location["latitude"].asDouble,
        lon = location["longitude"].asDouble,
        phone = p.str("nationalPhoneNumber"),
        hours = hours,
        openNow = p.obj("currentOpeningHours")?.get("openNow")?.asBoolean,
      )
    }.orEmpty()
  }
}

/** A place's card plus coordinates for directions. */
data class PlacePayload(val card: RenderCard.Place, val lat: Double, val lon: Double)

/** The object at [key], or null when it's missing or JSON null. */
internal fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.arr(key: String): com.google.gson.JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

internal fun JsonObject.str(key: String): String? = get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

private const val GOOGLE_MAPS = "com.google.android.apps.maps"

/** Google Maps is installed (visible through the manifest's <queries>). */
fun googleMapsInstalled(context: Context): Boolean = runCatching { context.packageManager.getPackageInfo(GOOGLE_MAPS, 0) }.isSuccess

/**
 * Open directions in the maps app, or start turn-by-turn navigation in Google Maps. OsmAnd's and
 * Organic Maps' navigation intents only plan the route, so start is only offered with Google Maps
 * installed. Ends the conversation.
 */
class DirectionsTool @Inject constructor() : BaseTool() {
  override val schema = schemaWith(canStart = false)

  override fun schema(env: ToolEnvironment): ToolSchema = schemaWith(canStart = env.googleMapsInstalled)

  private fun schemaWith(canStart: Boolean) =
    ToolSchema(
      name = "get_directions",
      description =
        "Open directions in the maps app. Give one destination: a place id from find_places, a " +
          "contact id (their address), \"home\" or \"work\" from what you know about the user, or an address.",
      params =
        listOfNotNull(
          ToolParam("place", ParamKind.HANDLE, "Place id.", required = false, handleType = HandleType.PLACE),
          ToolParam("contact", ParamKind.HANDLE, "Contact id with an address.", required = false, handleType = HandleType.CONTACT),
          ToolParam("address", ParamKind.STRING, "\"home\", \"work\", or a street address.", required = false),
          ToolParam(
            "mode",
            ParamKind.ENUM,
            "How to travel: driving (a car or motorbike, the default), walking, transit, or bicycling.",
            required = false,
            enumValues = listOf("driving", "walking", "transit", "bicycling"),
          ),
          if (canStart) {
            ToolParam("start", ParamKind.BOOLEAN, "Start turn-by-turn navigation now. True for \"navigate to\" or \"take me to\"; leave it out to show the route.", required = false)
          } else null,
        ),
    )

  override val metadata =
    ToolMetadata(
      id = "get_directions",
      title = "Directions",
      summary = "Open directions to a place, a contact, home, or work.",
      category = ToolCategory.INFORMATION,
      cancellable = false,
      endsConversation = true,
      unlockVerb = "open directions",
    )

  /** The plain prompt, without start's bare "true". */
  override fun approvalContent(call: ValidatedCall, context: ToolContext): ApprovalContent =
    super.approvalContent(call.copy(args = call.args - "start"), context)

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val place = call.handleArg("place")?.payload as? PlacePayload
    val contact = call.handleArg("contact")?.payload as? RenderCard.Contact
    val address = call.stringArg("address")?.let { text ->
      when (text.lowercase()) {
        "home" -> context.settings.aboutHome.ifBlank { null } ?: return ToolOutcome.Steering("error", "The user hasn't set a home address. Ask them for it, or tell them to add it in About you.")
        "work" -> context.settings.aboutWork.ifBlank { null } ?: return ToolOutcome.Steering("error", "The user hasn't set a work address. Ask them for it.")
        else -> text
      }
    }
    val (destination, label) =
      when {
        place != null -> "${place.lat},${place.lon}" to place.card.name
        contact != null -> (contact.address ?: return ToolOutcome.Steering("error", "${contact.name} has no address saved. Tell the user.")) to contact.name
        address != null -> address to address
        else -> return ToolOutcome.Steering("error", "Where to? Give a place, contact or address.")
      }
    val mode = call.stringArg("mode") ?: "driving"
    val pm = context.appContext.packageManager
    val googleMaps = googleMapsInstalled(context.appContext)
    // Google documents no transit mode for navigation intents, so transit shows the route, as it
    // does without Google Maps.
    val navigate = googleMaps && call.boolArg("start") == true && mode != "transit"
    val uri =
      if (googleMaps) {
        Uri.parse(
          "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}&travelmode=$mode" +
            if (navigate) "&dir_action=navigate" else ""
        )
      } else {
        Uri.parse("geo:0,0?q=${Uri.encode(destination)}")
      }
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (googleMaps) intent.setPackage(GOOGLE_MAPS)
    if (intent.resolveActivity(pm) == null) return ToolOutcome.Steering("error", "There's no maps app installed. Tell the user.")
    return ToolOutcome.Success(
      items = emptyList(),
      extras = JsonObject().apply { addProperty(if (navigate) "navigating_to" else "directions_to", label) },
      handoff = Handoff(phrase = if (navigate) "Starting navigation to $label." else "Here are directions to $label.", intent = intent),
    )
  }
}

/** "Where am I?" */
class LocationTool @Inject constructor() : BaseTool() {
  override val schema = ToolSchema(name = "get_location", description = "Get the user's current address.", params = emptyList())

  override val metadata =
    ToolMetadata(
      id = "get_location",
      title = "Where am I",
      summary = "Tell you where you are.",
      category = ToolCategory.INFORMATION,
      allowedWhenLockedDefault = true,
      requiredCapabilities = listOf(AppCapability.LOCATION),
      progressPhrase = "Finding where you are.",
    )

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val here = context.location.current() ?: return ToolOutcome.Steering("error", "Location isn't available. Tell the user to turn it on in settings.")
    val address =
      runCatching {
          val o = httpGetJson("${context.settings.effectiveNominatimUrl}/reverse?format=jsonv2&zoom=18&lat=${here.latitude}&lon=${here.longitude}").asJsonObject
          o.str("display_name")
        }
        .getOrNull()
    return ToolOutcome.Success(
      items =
        listOf(
          ResultItem(
            type = HandleType.PLACE,
            nativeId = "here:${here.latitude},${here.longitude}",
            projection = mapOf("address" to (address ?: here.label), "area" to here.label),
            renderPayload = PlacePayload(RenderCard.Place("You are here", address ?: here.label, null, null, null), here.latitude, here.longitude),
          )
        )
    )
  }
}
