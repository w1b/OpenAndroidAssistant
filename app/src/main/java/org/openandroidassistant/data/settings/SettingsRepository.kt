/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.data.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.openandroidassistant.proto.ApprovalOverride
import org.openandroidassistant.proto.AssistantSettings
import org.openandroidassistant.proto.LocationMode
import org.openandroidassistant.proto.MessageWording
import org.openandroidassistant.proto.ModelProvider
import org.openandroidassistant.proto.PhoneNumberSpeech
import org.openandroidassistant.proto.PlacesProvider
import org.openandroidassistant.proto.ReadPageProvider
import org.openandroidassistant.proto.ToolPrefs
import org.openandroidassistant.proto.TtsProviderType
import org.openandroidassistant.proto.UnitSystem
import org.openandroidassistant.proto.WebSearchMode
import org.openandroidassistant.tools.ApprovalLevel
import org.openandroidassistant.tools.AssistantTool

object SettingsSerializer : Serializer<AssistantSettings> {
  override val defaultValue: AssistantSettings = AssistantSettings.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): AssistantSettings =
    try {
      AssistantSettings.parseFrom(input)
    } catch (exception: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read settings proto.", exception)
    }

  override suspend fun writeTo(t: AssistantSettings, output: OutputStream) = t.writeTo(output)
}

/** What the proto's zero values stand for. */
object SettingsDefaults {
  const val OPENROUTER_MODEL = "google/gemini-3.8-flash"
  const val OPENROUTER_MODEL_NAME = "Gemini 3.8 Flash"
  const val OPENROUTER_REASONING = "high"
  const val OPENAI_MODEL = "gpt-6.1-sol"
  const val OPENAI_REASONING = "low"
  const val REALTIME_BACKEND_MODEL = "gpt-6.1-sol"
  const val REALTIME_BACKEND_REASONING = "low"
  const val REALTIME_CLOSE_AFTER_SECONDS = 4
  const val CONTINUE_WITHIN_MINUTES = 5
  const val TTS_PLAYBACK_SPEED = 1.5f
  const val NOMINATIM_URL = "https://nominatim.openstreetmap.org"
  const val JINA_URL = "https://r.jina.ai"
  const val KITTEN_TTS_VOICE = "Hugo"
}

@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<AssistantSettings>) {

  val settings: Flow<AssistantSettings> = store.data

  suspend fun current(): AssistantSettings = store.data.first()

  suspend fun update(transform: (AssistantSettings.Builder) -> Unit): AssistantSettings =
    store.updateData { current -> current.toBuilder().apply(transform).build() }

  suspend fun updateToolPrefs(toolId: String, transform: (ToolPrefs.Builder) -> Unit) {
    update { b ->
      val prefs = (b.toolPrefsMap[toolId] ?: ToolPrefs.getDefaultInstance()).toBuilder()
      transform(prefs)
      b.putToolPrefs(toolId, prefs.build())
    }
  }
}

// Effective values: proto zero values folded into their defaults.

val AssistantSettings.effectiveProvider: ModelProvider
  get() =
    when (provider) {
      ModelProvider.PROVIDER_UNSPECIFIED,
      ModelProvider.UNRECOGNIZED -> ModelProvider.PROVIDER_OPENROUTER
      else -> provider
    }

val AssistantSettings.effectiveOpenRouterModel: String
  get() = openrouterModel.ifBlank { SettingsDefaults.OPENROUTER_MODEL }

val AssistantSettings.effectiveOpenRouterReasoning: String
  get() = openrouterReasoning.ifBlank { SettingsDefaults.OPENROUTER_REASONING }

val AssistantSettings.effectiveOpenAiModel: String
  get() = openaiModel.ifBlank { SettingsDefaults.OPENAI_MODEL }

val AssistantSettings.effectiveOpenAiReasoning: String
  get() = openaiReasoning.ifBlank { SettingsDefaults.OPENAI_REASONING }

val AssistantSettings.effectiveRealtimeBackendModel: String
  get() = realtimeBackendModel.ifBlank { SettingsDefaults.REALTIME_BACKEND_MODEL }

val AssistantSettings.effectiveRealtimeBackendReasoning: String
  get() = realtimeBackendReasoning.ifBlank { SettingsDefaults.REALTIME_BACKEND_REASONING }

val AssistantSettings.effectiveRealtimeCloseAfterMs: Long
  get() =
    (if (realtimeCloseAfterSeconds > 0) realtimeCloseAfterSeconds
    else SettingsDefaults.REALTIME_CLOSE_AFTER_SECONDS) * 1000L

val AssistantSettings.effectiveContinueWithinMs: Long
  get() =
    (if (continueWithinMinutes > 0) continueWithinMinutes
    else SettingsDefaults.CONTINUE_WITHIN_MINUTES) * 60_000L

/** OpenRouter's facts about the chosen model; the default model's are known without a lookup. */
private val AssistantSettings.openRouterInfoKnown: Boolean
  get() = openrouterModelInfoCached && openrouterModel == effectiveOpenRouterModel

val AssistantSettings.openRouterHasNativeSearch: Boolean
  get() = if (openRouterInfoKnown) openrouterModelNativeSearch else effectiveOpenRouterModel == SettingsDefaults.OPENROUTER_MODEL

val AssistantSettings.openRouterAcceptsAudio: Boolean
  get() = if (openRouterInfoKnown) openrouterModelAcceptsAudio else effectiveOpenRouterModel == SettingsDefaults.OPENROUTER_MODEL

/**
 * The web search mode for the active provider. The default is native search where the provider
 * has it; local models have no native search, so they default to off.
 */
/** The web search the user picked for [provider], before checking it's set up. */
fun AssistantSettings.chosenWebSearchMode(provider: ModelProvider = effectiveProvider): WebSearchMode {
  val raw =
    when (provider) {
      ModelProvider.PROVIDER_OPENROUTER -> openrouterWebSearch
      ModelProvider.PROVIDER_OPENAI,
      ModelProvider.PROVIDER_OPENAI_REALTIME -> openaiWebSearch
      else -> localWebSearch
    }
  return when (raw) {
    WebSearchMode.WEB_SEARCH_UNSPECIFIED,
    WebSearchMode.UNRECOGNIZED ->
      when (provider) {
        ModelProvider.PROVIDER_OPENROUTER ->
          if (openRouterHasNativeSearch) WebSearchMode.WEB_SEARCH_NATIVE else WebSearchMode.WEB_SEARCH_OFF
        ModelProvider.PROVIDER_OPENAI,
        ModelProvider.PROVIDER_OPENAI_REALTIME -> WebSearchMode.WEB_SEARCH_NATIVE
        else -> WebSearchMode.WEB_SEARCH_OFF
      }
    WebSearchMode.WEB_SEARCH_NATIVE ->
      // OpenRouter's native option exists only for models that have native search.
      if (provider == ModelProvider.PROVIDER_OPENROUTER && !openRouterHasNativeSearch) {
        WebSearchMode.WEB_SEARCH_OFF
      } else if (provider == ModelProvider.PROVIDER_LOCAL) {
        WebSearchMode.WEB_SEARCH_OFF
      } else {
        raw
      }
    else -> raw
  }
}

/** The web search in effect: the chosen one, or off when SearXNG has no instance. A missing Tavily key is checked by the tool registry. */
fun AssistantSettings.webSearchMode(provider: ModelProvider = effectiveProvider): WebSearchMode =
  chosenWebSearchMode(provider).let {
    if (it == WebSearchMode.WEB_SEARCH_SEARXNG && searxngUrl.isBlank()) WebSearchMode.WEB_SEARCH_OFF else it
  }

val AssistantSettings.effectiveReadPageProvider: ReadPageProvider
  get() =
    if (readPageProvider == ReadPageProvider.READ_PAGE_FIRECRAWL) readPageProvider
    else ReadPageProvider.READ_PAGE_JINA

val AssistantSettings.effectiveNominatimUrl: String
  get() = nominatimUrl.trim().trimEnd('/').ifEmpty { SettingsDefaults.NOMINATIM_URL }

val AssistantSettings.effectiveJinaUrl: String
  get() = jinaUrl.trim().trimEnd('/').ifEmpty { SettingsDefaults.JINA_URL }

/** Why [url] can't be an instance's address, or null when it can (blank clears it). Plain HTTP only reaches the phone itself. */
fun instanceUrlProblem(url: String): String? {
  if (url.isBlank()) return null
  val parsed = url.trim().toHttpUrlOrNull() ?: return "Enter the full address, starting with https://"
  return if (parsed.isHttps || parsed.host == "localhost" || parsed.host == "127.0.0.1") null else "Use an https:// address."
}

val AssistantSettings.effectivePlacesProvider: PlacesProvider
  get() = if (placesProvider == PlacesProvider.PLACES_GOOGLE) placesProvider else PlacesProvider.PLACES_OSM

val AssistantSettings.effectiveLocationMode: LocationMode
  get() =
    when (locationMode) {
      LocationMode.LOCATION_HOME,
      LocationMode.LOCATION_OFF -> locationMode
      else -> LocationMode.LOCATION_DEVICE
    }

val AssistantSettings.effectiveMessageWording: MessageWording
  get() =
    when (messageWording) {
      MessageWording.WORDING_EXACT,
      MessageWording.WORDING_NATURAL -> messageWording
      else -> MessageWording.WORDING_LIGHT_CLEANUP
    }

val AssistantSettings.effectivePhoneNumbers: PhoneNumberSpeech
  get() =
    if (phoneNumbers == PhoneNumberSpeech.PHONE_NUMBERS_SHOW_ONLY) phoneNumbers
    else PhoneNumberSpeech.PHONE_NUMBERS_SPEAK

val AssistantSettings.effectiveUnits: UnitSystem
  get() =
    when (units) {
      UnitSystem.UNITS_METRIC,
      UnitSystem.UNITS_IMPERIAL -> units
      else ->
        if (java.util.Locale.getDefault().country in setOf("US", "LR", "MM")) UnitSystem.UNITS_IMPERIAL
        else UnitSystem.UNITS_METRIC
    }


val AssistantSettings.effectiveTtsProvider: TtsProviderType
  get() =
    if (ttsProvider == TtsProviderType.TTS_PROVIDER_KITTEN_NANO) ttsProvider
    else TtsProviderType.TTS_PROVIDER_ANDROID

val AssistantSettings.effectiveTtsPlaybackSpeed: Float
  get() =
    if (ttsPlaybackSpeed.isFinite() && ttsPlaybackSpeed > 0f) ttsPlaybackSpeed.coerceIn(0.5f, 2f)
    else SettingsDefaults.TTS_PLAYBACK_SPEED

val AssistantSettings.effectiveKittenTtsVoice: String
  get() = kittenTtsVoice.ifEmpty { SettingsDefaults.KITTEN_TTS_VOICE }

val AssistantSettings.effectiveSpeechHighlightingEnabled: Boolean
  get() = !speechHighlightingDisabled

/** The approval level a tool runs at: the user's choice, else the tool's default. */
fun AssistantSettings.approvalLevel(tool: AssistantTool): ApprovalLevel =
  when (toolPrefsMap[tool.metadata.id]?.approval) {
    ApprovalOverride.APPROVAL_NONE -> ApprovalLevel.NONE
    ApprovalOverride.APPROVAL_LOW -> ApprovalLevel.LOW
    ApprovalOverride.APPROVAL_HIGH -> ApprovalLevel.HIGH
    ApprovalOverride.APPROVAL_CRITICAL -> ApprovalLevel.CRITICAL
    else -> tool.metadata.defaultApproval
  }

fun AssistantSettings.allowedWhenLocked(tool: AssistantTool): Boolean {
  val prefs = toolPrefsMap[tool.metadata.id]
  return if (prefs?.lockedOverrideCase == ToolPrefs.LockedOverrideCase.ALLOWED_WHEN_LOCKED) {
    prefs.allowedWhenLocked
  } else {
    tool.metadata.allowedWhenLockedDefault
  }
}

fun ApprovalLevel.toOverride(): ApprovalOverride =
  when (this) {
    ApprovalLevel.NONE -> ApprovalOverride.APPROVAL_NONE
    ApprovalLevel.LOW -> ApprovalOverride.APPROVAL_LOW
    ApprovalLevel.HIGH -> ApprovalOverride.APPROVAL_HIGH
    ApprovalLevel.CRITICAL -> ApprovalOverride.APPROVAL_CRITICAL
  }
