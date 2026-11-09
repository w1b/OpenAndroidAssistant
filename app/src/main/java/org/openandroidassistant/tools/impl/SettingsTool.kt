/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools.impl

import android.content.Intent
import android.provider.Settings
import javax.inject.Inject
import org.openandroidassistant.tools.BaseTool
import org.openandroidassistant.tools.Handoff
import org.openandroidassistant.tools.ParamKind
import org.openandroidassistant.tools.ToolCategory
import org.openandroidassistant.tools.ToolContext
import org.openandroidassistant.tools.ToolMetadata
import org.openandroidassistant.tools.ToolOutcome
import org.openandroidassistant.tools.ToolParam
import org.openandroidassistant.tools.ToolSchema
import org.openandroidassistant.tools.ValidatedCall

/** Opens a page of the phone's Settings app. The page opens and the conversation ends. */
class OpenSettingsTool @Inject constructor() : BaseTool() {
  override val schema =
    ToolSchema(
      name = "open_settings",
      description = "Open a page of the phone's Settings app. For one app's settings, use open_app with page app_info or notifications.",
      params = listOf(ToolParam("screen", ParamKind.ENUM, "The page to open.", enumValues = SCREENS.keys.toList())),
    )

  override val metadata =
    ToolMetadata(
      id = "open_settings",
      title = "Open settings",
      summary = "Open a page of your phone's settings.",
      category = ToolCategory.DEVICE,
      cancellable = false,
      unlockVerb = "open settings",
      endsConversation = true,
    )

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val screen = call.stringArg("screen") ?: return ToolOutcome.Steering("error", "Which settings page?")
    val (action, name) = SCREENS[screen] ?: return ToolOutcome.Steering("error", "Unknown settings page.")
    val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(context.appContext.packageManager) == null) {
      return ToolOutcome.Steering("error", "This phone has no $name page. Tell the user.")
    }
    return ToolOutcome.Success(items = emptyList(), handoff = Handoff("Opening $name settings.", intent))
  }

  companion object {
    /** Screen → the Settings action and its spoken name. */
    val SCREENS: Map<String, Pair<String, String>> =
      linkedMapOf(
        "settings" to (Settings.ACTION_SETTINGS to "the main"),
        "wifi" to (Settings.ACTION_WIFI_SETTINGS to "Wi-Fi"),
        "bluetooth" to (Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth"),
        "mobile_network" to (Settings.ACTION_NETWORK_OPERATOR_SETTINGS to "mobile network"),
        "data_usage" to (Settings.ACTION_DATA_USAGE_SETTINGS to "data usage"),
        "airplane_mode" to (Settings.ACTION_AIRPLANE_MODE_SETTINGS to "airplane mode"),
        "display" to (Settings.ACTION_DISPLAY_SETTINGS to "display"),
        "sound" to (Settings.ACTION_SOUND_SETTINGS to "sound"),
        "battery" to (Intent.ACTION_POWER_USAGE_SUMMARY to "battery"),
        "location" to (Settings.ACTION_LOCATION_SOURCE_SETTINGS to "location"),
        "nfc" to (Settings.ACTION_NFC_SETTINGS to "NFC"),
        "cast" to (Settings.ACTION_CAST_SETTINGS to "cast"),
        "date_and_time" to (Settings.ACTION_DATE_SETTINGS to "date and time"),
        "language" to (Settings.ACTION_LOCALE_SETTINGS to "language"),
        "keyboard" to (Settings.ACTION_INPUT_METHOD_SETTINGS to "keyboard"),
        "accessibility" to (Settings.ACTION_ACCESSIBILITY_SETTINGS to "accessibility"),
        "security" to (Settings.ACTION_SECURITY_SETTINGS to "security"),
        "privacy" to (Settings.ACTION_PRIVACY_SETTINGS to "privacy"),
        "apps" to (Settings.ACTION_APPLICATION_SETTINGS to "apps"),
        "default_apps" to (Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS to "default apps"),
        "storage" to (Settings.ACTION_INTERNAL_STORAGE_SETTINGS to "storage"),
        "about_phone" to (Settings.ACTION_DEVICE_INFO_SETTINGS to "about phone"),
      )
  }
}
