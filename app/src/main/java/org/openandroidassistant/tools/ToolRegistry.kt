/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.openandroidassistant.data.settings.Secret
import org.openandroidassistant.data.settings.SecretStore
import org.openandroidassistant.data.settings.effectiveLocationMode
import org.openandroidassistant.data.settings.webSearchMode
import org.openandroidassistant.location.AppLocationProvider
import org.openandroidassistant.permissions.AppCapability
import org.openandroidassistant.proto.AssistantSettings
import org.openandroidassistant.proto.LocationMode
import org.openandroidassistant.proto.WebSearchMode
import org.openandroidassistant.tools.impl.TimeTool
import org.openandroidassistant.tools.impl.WebSearchTool

/**
 * The tool catalog, and which tools a conversation exposes: the user's choice per tool, Android's
 * permissions, and configuration. Exposure is fixed when a conversation starts.
 */
@Singleton
class ToolRegistry
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val secrets: SecretStore,
  private val location: AppLocationProvider,
  private val allTools: Set<@JvmSuppressWildcards AssistantTool>,
) {
  fun byId(id: String): AssistantTool? = allTools.firstOrNull { it.metadata.id == id }

  /** The whole catalog, for settings. */
  fun catalog(): List<AssistantTool> = allTools.sortedBy { it.metadata.title }

  fun exposedTools(settings: AssistantSettings, realtime: Boolean): List<AssistantTool> =
    catalog().filter { exposureState(it, settings, realtime) == ToolExposureState.EXPOSED }

  fun exposureState(tool: AssistantTool, settings: AssistantSettings, realtime: Boolean = false): ToolExposureState {
    if (settings.toolPrefsMap[tool.metadata.id]?.disabled == true) return ToolExposureState.DISABLED
    when (tool.metadata.id) {
      // Turn-based models get the time with every message.
      TimeTool.ID -> if (!realtime) return ToolExposureState.NOT_NEEDED
      WebSearchTool.ID ->
        when (settings.webSearchMode()) {
          WebSearchMode.WEB_SEARCH_SEARXNG -> {}
          WebSearchMode.WEB_SEARCH_TAVILY -> if (secrets.get(Secret.TAVILY_KEY) == null) return ToolExposureState.NOT_CONFIGURED
          else -> return ToolExposureState.NOT_CONFIGURED
        }
    }
    // Location: the setting decides; the prompt says when it's off (AppLocationProvider.unavailableReason).
    if (AppCapability.LOCATION in tool.metadata.requiredCapabilities && settings.effectiveLocationMode == LocationMode.LOCATION_OFF) {
      return ToolExposureState.NOT_CONFIGURED
    }
    if (missingRequiredCapabilities(tool, settings).isNotEmpty()) return ToolExposureState.MISSING_ACCESS
    return ToolExposureState.EXPOSED
  }

  /** Android permissions the tool needs that aren't granted (location: none to use). */
  fun missingRequiredCapabilities(tool: AssistantTool, settings: AssistantSettings): List<AppCapability> =
    tool.metadata.requiredCapabilities.filterNot {
      if (it == AppCapability.LOCATION) location.isAvailable(settings) || settings.effectiveLocationMode == LocationMode.LOCATION_OFF
      else it.isGranted(context)
    }

  /**
   * Tools the user could ask for but can't use right now, and why, for the prompt: so the model
   * says the tool is off and where to turn it on, instead of guessing or trying something else.
   */
  fun unavailable(settings: AssistantSettings, realtime: Boolean): List<String> =
    catalog().mapNotNull { tool ->
      val why =
        when (exposureState(tool, settings, realtime)) {
          ToolExposureState.DISABLED -> "the user set it to not allowed (Settings, Tools)"
          ToolExposureState.MISSING_ACCESS ->
            "the app doesn't have ${missingRequiredCapabilities(tool, settings).joinToString(" and ") { it.title.lowercase() }} permission yet (Settings, Tools)"
          else -> null
        }
      why?.let { "${tool.metadata.title}: $it." }
    }
}

enum class ToolExposureState {
  EXPOSED,
  DISABLED,
  MISSING_ACCESS,
  NOT_CONFIGURED,
  /** Not part of this kind of conversation (get_time outside realtime). */
  NOT_NEEDED,
}
