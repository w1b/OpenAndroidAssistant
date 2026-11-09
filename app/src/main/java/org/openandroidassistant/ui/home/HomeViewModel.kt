/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.openandroidassistant.data.model.Artifacts
import org.openandroidassistant.data.model.DownloadRepository
import org.openandroidassistant.data.model.DownloadState
import org.openandroidassistant.data.settings.SecretStore
import org.openandroidassistant.data.settings.SettingsRepository
import org.openandroidassistant.data.settings.effectiveProvider
import org.openandroidassistant.data.settings.openRouterAcceptsAudio
import org.openandroidassistant.model.ActiveModel
import org.openandroidassistant.model.ModelRouter
import org.openandroidassistant.permissions.AppCapability
import org.openandroidassistant.proto.ModelProvider

/** What's missing before the assistant works well, in the order to fix it. */
enum class SetupStep(val title: String, val action: String, val detail: String? = null) {
  MICROPHONE("Allow the microphone", "Allow"),
  MODEL("Choose a model", "Choose", "Use a cloud model with your API key, or download one that runs on your phone."),
  /** Title names the chosen provider: see [HomeUiState.providerName]. */
  KEY("Add your API key", "Open model settings"),
  LOCAL_MODEL("Download the on-device model", "Open model settings"),
  SPEECH("Download speech recognition", "Download"),
}

data class HomeUiState(
  val modelLabel: String = "",
  val steps: List<SetupStep> = emptyList(),
  val speech: DownloadState = DownloadState.NotInstalled,
  val providerName: String = "",
  /** A model can answer and the mic is allowed: talking works. */
  val ready: Boolean = false,
)

@HiltViewModel
class HomeViewModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val downloads: DownloadRepository,
  settings: SettingsRepository,
  secrets: SecretStore,
  private val router: ModelRouter,
) : ViewModel() {
  private val revision = MutableStateFlow(0)

  val uiState: StateFlow<HomeUiState> =
    combine(settings.settings, secrets.present, downloads.state(Artifacts.LLM), downloads.state(Artifacts.SPEECH), revision) { s, _, llm, stt, _ ->
      val active = router.active(s)
      val provider = s.effectiveProvider
      // Speech recognition is needed for voice approval, and for every model that gets a transcript.
      val transcriptModel =
        provider == ModelProvider.PROVIDER_OPENAI || (provider == ModelProvider.PROVIDER_OPENROUTER && !s.openRouterAcceptsAudio)
      val steps = buildList {
        if (!AppCapability.MICROPHONE.isGranted(context)) add(SetupStep.MICROPHONE)
        if (active is ActiveModel.Unavailable) {
          add(
            when {
              // Nothing chosen yet: cloud or on-device is the user's first choice, not a key.
              s.provider == ModelProvider.PROVIDER_UNSPECIFIED -> SetupStep.MODEL
              provider == ModelProvider.PROVIDER_LOCAL -> SetupStep.LOCAL_MODEL
              else -> SetupStep.KEY
            }
          )
        }
        if (stt !is DownloadState.Installed) add(SetupStep.SPEECH)
      }
      HomeUiState(
        modelLabel =
          when (active) {
            is ActiveModel.TurnBased -> active.model.displayName + if (transcriptModel) " · uses speech transcription" else ""
            is ActiveModel.Realtime -> "GPT-Live"
            is ActiveModel.Unavailable -> "Not set up"
          },
        steps = steps,
        speech = stt,
        providerName = router.providerName(provider),
        ready = SetupStep.MICROPHONE !in steps && active !is ActiveModel.Unavailable,
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

  fun downloadSpeech() = downloads.enqueue(Artifacts.SPEECH)

  fun refresh() {
    revision.value += 1
  }
}
