/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openandroidassistant.data.HistoryRecorder
import org.openandroidassistant.data.model.Artifacts
import org.openandroidassistant.data.model.DownloadRepository
import org.openandroidassistant.data.model.DownloadState
import org.openandroidassistant.data.model.LocalModel
import org.openandroidassistant.data.settings.ExtraHeader
import org.openandroidassistant.data.settings.Secret
import org.openandroidassistant.data.settings.SecretStore
import org.openandroidassistant.data.settings.SettingsRepository
import org.openandroidassistant.data.settings.encodeHeaders
import org.openandroidassistant.data.settings.searxngHeaders
import org.openandroidassistant.data.settings.splitLogin
import org.openandroidassistant.data.settings.toOverride
import org.openandroidassistant.location.AppLocationProvider
import org.openandroidassistant.model.ModelRouter
import org.openandroidassistant.model.OpenRouterModelInfo
import org.openandroidassistant.permissions.AppCapability
import org.openandroidassistant.proto.AssistantSettings
import org.openandroidassistant.tools.ApprovalLevel
import org.openandroidassistant.tools.AssistantTool
import org.openandroidassistant.tools.ToolRegistry
import org.openandroidassistant.tools.impl.SearxngReply
import org.openandroidassistant.tools.impl.searxngSearch

/** The result of checking a self-hosted instance. */
data class InstanceCheck(val text: String, val problem: Boolean)

/** One view model behind every settings page. */
@HiltViewModel
class SettingsViewModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val repository: SettingsRepository,
  private val secretStore: SecretStore,
  private val router: ModelRouter,
  private val registry: ToolRegistry,
  private val downloads: DownloadRepository,
  private val history: HistoryRecorder,
  private val location: AppLocationProvider,
) : ViewModel() {

  val settings: StateFlow<AssistantSettings> =
    repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AssistantSettings.getDefaultInstance())

  val secrets: StateFlow<Map<Secret, String>> = secretStore.present

  /** Download state of the chosen on-device model. */
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val localModel: StateFlow<DownloadState> =
    repository.settings
      .map { LocalModel.byId(it.localModel) }
      .distinctUntilChanged()
      .flatMapLatest { downloads.state(it.artifact) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadState.NotInstalled)

  val speechModel: StateFlow<DownloadState> =
    downloads.state(Artifacts.SPEECH).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadState.NotInstalled)

  val kittenVoice: StateFlow<DownloadState> =
    downloads.state(Artifacts.KITTEN_TTS_NANO_EN).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadState.NotInstalled)

  /** Bumped when returning to the app, so access states re-read. */
  val accessRevision = MutableStateFlow(0)

  private val _openRouterModels = MutableStateFlow<List<OpenRouterModelInfo>?>(null)
  val openRouterModels: StateFlow<List<OpenRouterModelInfo>?> = _openRouterModels
  val modelListError = MutableStateFlow<String?>(null)

  fun update(transform: (AssistantSettings.Builder) -> Unit) {
    viewModelScope.launch { repository.update(transform) }
  }

  fun setSecret(secret: Secret, value: String?) = secretStore.set(secret, value)

  private val _searxngCheck = MutableStateFlow<InstanceCheck?>(null)
  /** The test search after a SearXNG change, shown under the instance. Null until something is saved. */
  val searxngCheck: StateFlow<InstanceCheck?> = _searxngCheck
  private var searxngCheckJob: Job? = null

  /** Saves the instance; a login written into the address ("https://user:pass@host") goes to Basic auth. */
  fun setSearxngUrl(input: String) {
    val address = splitLogin(input)
    if (address.username != null) {
      secretStore.set(Secret.SEARXNG_USERNAME, address.username)
      secretStore.set(Secret.SEARXNG_PASSWORD, address.password)
    }
    viewModelScope.launch {
      repository.update { it.setSearxngUrl(address.url) }
      checkSearxng(address.url)
    }
  }

  fun setSearxngLogin(username: String?, password: String?) {
    secretStore.set(Secret.SEARXNG_USERNAME, username)
    secretStore.set(Secret.SEARXNG_PASSWORD, password?.takeIf { username != null })
    checkSearxng()
  }

  fun setSearxngHeaders(headers: List<ExtraHeader>) {
    secretStore.set(Secret.SEARXNG_HEADERS, encodeHeaders(headers))
    checkSearxng()
  }

  private fun checkSearxng(url: String = settings.value.searxngUrl) {
    searxngCheckJob?.cancel()
    if (url.isBlank()) {
      _searxngCheck.value = null
      return
    }
    _searxngCheck.value = InstanceCheck("Checking…", problem = false)
    searxngCheckJob =
      viewModelScope.launch {
        _searxngCheck.value =
          when (val reply = searxngSearch(url, "searxng", secretStore.searxngHeaders())) {
            is SearxngReply.Results -> InstanceCheck("Working", problem = false)
            is SearxngReply.Refused ->
              InstanceCheck(
                when (reply.code) {
                  401 -> "Login rejected (HTTP 401)"
                  403 -> "Refused (HTTP 403): json format off, or the login is wrong"
                  429 -> "Rate limited (HTTP 429)"
                  in 300..399 -> "Redirected (HTTP ${reply.code}), probably to a login page"
                  else -> "Failed (HTTP ${reply.code})"
                },
                problem = true,
              )
            SearxngReply.NotResults -> InstanceCheck("Answered with a web page, not results: probably a login page", problem = true)
            is SearxngReply.Unreachable -> InstanceCheck("Can't reach it: ${reply.message}", problem = true)
          }
      }
  }

  fun loadOpenRouterModels() {
    if (_openRouterModels.value != null) return
    viewModelScope.launch {
      runCatching { router.openRouterModels() }
        .onSuccess { list -> _openRouterModels.value = list.sortedBy { it.name.lowercase() } }
        .onFailure { modelListError.value = "Couldn't load models: ${it.message}" }
    }
  }

  /** Selects an OpenRouter model and looks up what it can do (audio, native search). */
  fun selectOpenRouterModel(id: String) {
    viewModelScope.launch {
      repository.update { it.setOpenrouterModel(id).setOpenrouterModelInfoCached(false) }
      router.refreshOpenRouterInfo(id)
    }
  }

  // ---------------------------------------------------------------- tools

  fun catalog(): List<AssistantTool> = registry.catalog().filter { it.metadata.id != "get_time" }

  fun exposure(tool: AssistantTool, s: AssistantSettings) = registry.exposureState(tool, s)

  fun missingAccess(tool: AssistantTool, s: AssistantSettings) = registry.missingRequiredCapabilities(tool, s)

  /** Sets the tool's approval level, or not allowed when [level] is null. */
  fun setToolChoice(tool: AssistantTool, level: ApprovalLevel?) =
    viewModelScope.launch {
      repository.updateToolPrefs(tool.metadata.id) {
        it.setDisabled(level == null)
        if (level != null) it.setApproval(level.toOverride())
      }
    }

  /** Every tool back to its defaults. */
  fun resetToolChoices() = update { it.clearToolPrefs() }

  fun setAllowedWhenLocked(tool: AssistantTool, allowed: Boolean) =
    viewModelScope.launch { repository.updateToolPrefs(tool.metadata.id) { it.setAllowedWhenLocked(allowed) } }

  // ---------------------------------------------------------------- access

  fun isGranted(capability: AppCapability): Boolean = capability.isGranted(context)


  fun markRequested(capability: AppCapability) = update {
    if (capability.name !in it.requestedCapabilityIdsList) it.addRequestedCapabilityIds(capability.name)
  }

  // ---------------------------------------------------------------- downloads

  fun download(which: String) =
    when (which) {
      "llm" -> downloads.enqueue(LocalModel.byId(settings.value.localModel).artifact)
      "stt" -> downloads.enqueue(Artifacts.SPEECH)
      else -> downloads.enqueue(Artifacts.KITTEN_TTS_NANO_EN)
    }

  // ---------------------------------------------------------------- about you, history

  /** Saves the home address and geocodes it for the home location setting. */
  fun setHome(address: String) {
    viewModelScope.launch {
      repository.update { it.setAboutHome(address) }
      if (address.isBlank()) {
        repository.update { it.setHomeGeocodedFrom("").setHomeLatitude(0.0).setHomeLongitude(0.0) }
        return@launch
      }
      location.geocodeHome(address)?.let { (lat, lon) ->
        repository.update { it.setHomeLatitude(lat).setHomeLongitude(lon).setHomeGeocodedFrom(address) }
      }
    }
  }

  fun clearHistory() = viewModelScope.launch { history.clear() }

  fun setHistoryMode(mode: org.openandroidassistant.proto.HistoryMode) = update { it.setHistory(mode) }
}
