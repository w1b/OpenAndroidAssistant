/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools.impl

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openandroidassistant.orchestrator.HandleTable
import org.openandroidassistant.proto.AssistantSettings
import org.openandroidassistant.tools.LocationProvider
import org.openandroidassistant.tools.ToolContext
import org.openandroidassistant.tools.ToolEnvironment
import org.openandroidassistant.tools.ToolOutcome
import org.openandroidassistant.tools.ValidatedCall
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class DirectionsToolTest {
  private val app = RuntimeEnvironment.getApplication()
  private val tool = DirectionsTool()

  @Test
  fun startIsOfferedOnlyWithGoogleMaps() {
    fun params(googleMaps: Boolean) = tool.schema(ToolEnvironment(locationAvailable = true, googleMapsInstalled = googleMaps)).params.map { it.name }
    assertTrue("start" in params(googleMaps = true))
    assertFalse("start" in params(googleMaps = false))
  }

  @Test
  fun startNavigatesInGoogleMapsWithTheMode() {
    installGoogleMaps()
    val outcome = run(mapOf("address" to "1 Main St", "mode" to "bicycling", "start" to true))
    val uri = outcome.handoff!!.intent.dataString!!
    assertTrue(uri, uri.endsWith("&travelmode=bicycling&dir_action=navigate"))
    assertEquals("com.google.android.apps.maps", outcome.handoff!!.intent.`package`)
    assertEquals("Starting navigation to 1 Main St.", outcome.handoff!!.phrase)
    assertEquals("1 Main St", outcome.extras!!.get("navigating_to").asString)
  }

  @Test
  fun withoutStartGoogleMapsShowsTheRoute() {
    installGoogleMaps()
    val outcome = run(mapOf("address" to "1 Main St"))
    val uri = outcome.handoff!!.intent.dataString!!
    assertTrue(uri, uri.endsWith("&travelmode=driving"))
    assertEquals("Here are directions to 1 Main St.", outcome.handoff!!.phrase)
    assertEquals("1 Main St", outcome.extras!!.get("directions_to").asString)
  }

  @Test
  fun transitShowsTheRouteEvenWithStart() {
    installGoogleMaps()
    val outcome = run(mapOf("address" to "1 Main St", "mode" to "transit", "start" to true))
    assertFalse(outcome.handoff!!.intent.dataString!!.contains("dir_action"))
    assertEquals("Here are directions to 1 Main St.", outcome.handoff!!.phrase)
  }

  @Test
  fun withoutGoogleMapsStartFallsBackToGeo() {
    // Google Maps was uninstalled after the conversation offered start.
    installApp("org.example.maps", IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("geo") })
    val outcome = run(mapOf("address" to "1 Main St", "start" to true))
    assertEquals("geo:0,0?q=1%20Main%20St", outcome.handoff!!.intent.dataString)
    assertNull(outcome.handoff!!.intent.`package`)
    assertEquals("Here are directions to 1 Main St.", outcome.handoff!!.phrase)
  }

  @Test
  fun approvalLeavesOutStart() {
    val call = ValidatedCall("get_directions", mapOf("address" to "1 Main St", "mode" to "bicycling", "start" to true), emptyMap())
    val content = tool.approvalContent(call, context())
    assertEquals("Directions: 1 Main St, bicycling?", content.spokenPrompt)
  }

  private fun installGoogleMaps() = installApp("com.google.android.apps.maps", IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("https") })

  private fun installApp(packageName: String, filter: IntentFilter) {
    val pm = shadowOf(app.packageManager)
    pm.installPackage(PackageInfo().apply { this.packageName = packageName })
    val activity = ComponentName(packageName, "$packageName.Main")
    pm.addActivityIfNotPresent(activity)
    pm.addIntentFilterForActivity(activity, filter.apply { addCategory(Intent.CATEGORY_DEFAULT) })
  }

  private fun run(args: Map<String, Any?>): ToolOutcome.Success =
    runBlocking { tool.execute(ValidatedCall("get_directions", args, emptyMap()), context()) } as ToolOutcome.Success

  private fun context() =
    ToolContext(
      app,
      AssistantSettings.getDefaultInstance(),
      HandleTable(),
      object : LocationProvider {
        override suspend fun current() = null
      },
    )
}
