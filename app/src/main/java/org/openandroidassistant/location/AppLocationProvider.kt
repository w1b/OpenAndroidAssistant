/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.openandroidassistant.data.settings.SettingsRepository
import org.openandroidassistant.data.settings.effectiveLocationMode
import org.openandroidassistant.proto.AssistantSettings
import org.openandroidassistant.proto.LocationMode
import org.openandroidassistant.tools.LocationProvider
import org.openandroidassistant.tools.UserLocation
import org.openandroidassistant.tools.impl.httpGetJson

private const val TAG = "AppLocationProvider"
private const val FIX_TIMEOUT_MS = 6_000L
/** A cached fix younger than this is good enough for weather and nearby places. */
private const val MAX_FIX_AGE_MS = 10 * 60_000L

/** The single location setting: device location, home, or off. */
@Singleton
class AppLocationProvider
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val settings: SettingsRepository,
) : LocationProvider {

  private var labelCache: Pair<String, String>? = null

  /** True when tools can get a location without asking (decides required params). */
  fun isAvailable(s: AssistantSettings): Boolean =
    when (s.effectiveLocationMode) {
      LocationMode.LOCATION_OFF -> false
      LocationMode.LOCATION_HOME -> s.homeGeocodedFrom.isNotBlank()
      else -> hasPermission()
    }

  /** Why tools can't get a location, for the prompt; null when they can. */
  fun unavailableReason(s: AssistantSettings): String? =
    when {
      isAvailable(s) -> null
      s.effectiveLocationMode == LocationMode.LOCATION_OFF -> "the user turned location off (Settings, Tools)"
      s.effectiveLocationMode == LocationMode.LOCATION_HOME -> "location is set to the user's home, but no home address is saved (Settings, About you)"
      else -> "the app doesn't have location permission yet (Settings, Tools)"
    }

  override suspend fun current(): UserLocation? {
    val s = settings.current()
    return when (s.effectiveLocationMode) {
      LocationMode.LOCATION_OFF -> null
      LocationMode.LOCATION_HOME ->
        if (s.homeGeocodedFrom.isBlank()) null
        else UserLocation(s.homeLatitude, s.homeLongitude, label = s.aboutHome.ifBlank { null })
      else -> deviceFix()?.let { UserLocation(it.latitude, it.longitude, label = cityLabel(it.latitude, it.longitude)) }
    }
  }

  private fun hasPermission(): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
      context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

  @Suppress("MissingPermission")
  private suspend fun deviceFix(): Location? {
    if (!hasPermission()) return null
    val lm = context.getSystemService(LocationManager::class.java)
    val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
      .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    val recent =
      providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
        ?.takeIf { System.currentTimeMillis() - it.time < MAX_FIX_AGE_MS }
    if (recent != null) return recent
    for (provider in providers) {
      val fix =
        withTimeoutOrNull(FIX_TIMEOUT_MS) {
          suspendCancellableCoroutine<Location?> { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            lm.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
          }
        }
      if (fix != null) return fix
    }
    return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
  }

  /** "Austin, Texas" from OpenStreetMap's reverse geocoder, cached per ~1 km cell. */
  private suspend fun cityLabel(lat: Double, lon: Double): String? {
    val key = "%.2f,%.2f".format(lat, lon)
    labelCache?.let { (k, v) -> if (k == key) return v }
    return runCatching {
        val json =
          httpGetJson(
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=10&lat=$lat&lon=$lon" +
              "&accept-language=" + URLEncoder.encode(java.util.Locale.getDefault().toLanguageTag(), "UTF-8")
          ).asJsonObject
        val address = json.getAsJsonObject("address")
        val city =
          listOf("city", "town", "village", "municipality", "county").firstNotNullOfOrNull { k ->
            address?.get(k)?.takeIf { !it.isJsonNull }?.asString
          }
        val region = address?.get("state")?.takeIf { !it.isJsonNull }?.asString
        listOfNotNull(city, region).joinToString(", ").ifEmpty { null }
      }
      .onFailure { Log.w(TAG, "reverse geocode failed", it) }
      .getOrNull()
      ?.also { labelCache = key to it }
  }

  /** Geocodes the About-you home address for the home location setting. */
  suspend fun geocodeHome(address: String): Pair<Double, Double>? =
    runCatching {
        val results =
          httpGetJson("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=" + URLEncoder.encode(address, "UTF-8"))
            .asJsonArray
        if (results.isEmpty) null
        else results[0].asJsonObject.let { it["lat"].asString.toDouble() to it["lon"].asString.toDouble() }
      }
      .getOrNull()
}
