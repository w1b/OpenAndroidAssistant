/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.model

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.openandroidassistant.data.settings.Secret
import org.openandroidassistant.data.settings.SecretStore
import org.openandroidassistant.data.settings.SettingsDefaults
import org.openandroidassistant.data.settings.SettingsRepository
import org.openandroidassistant.data.settings.effectiveOpenAiModel
import org.openandroidassistant.data.settings.effectiveOpenAiReasoning
import org.openandroidassistant.data.settings.effectiveOpenRouterModel
import org.openandroidassistant.data.settings.effectiveOpenRouterReasoning
import org.openandroidassistant.data.settings.effectiveProvider
import org.openandroidassistant.data.settings.openRouterAcceptsAudio
import org.openandroidassistant.data.settings.openRouterHasNativeSearch
import org.openandroidassistant.proto.AssistantSettings
import org.openandroidassistant.proto.ModelProvider

private const val TAG = "ModelRouter"

/** What a new conversation runs on. */
sealed interface ActiveModel {
  data class TurnBased(val model: ChatModel, val providerName: String) : ActiveModel

  data class Realtime(val apiKey: String) : ActiveModel

  /** Not usable: e.g. a cloud provider without a key, or no local model installed. */
  data class Unavailable(val spokenReason: String) : ActiveModel
}

/** Picks the model for the selected provider. */
@Singleton
class ModelRouter
@Inject
constructor(
  private val settings: SettingsRepository,
  private val secrets: SecretStore,
  val local: LitertChatModel,
) {
  fun providerName(provider: ModelProvider): String =
    when (provider) {
      ModelProvider.PROVIDER_LOCAL -> "the on-device model"
      ModelProvider.PROVIDER_OPENAI,
      ModelProvider.PROVIDER_OPENAI_REALTIME -> "OpenAI"
      else -> "OpenRouter"
    }

  fun active(s: AssistantSettings): ActiveModel {
    local.selected = org.openandroidassistant.data.model.LocalModel.byId(s.localModel)
    return activeFor(s)
  }

  private fun activeFor(s: AssistantSettings): ActiveModel =
    when (s.effectiveProvider) {
      ModelProvider.PROVIDER_LOCAL ->
        if (local.isInstalled()) ActiveModel.TurnBased(local, providerName(ModelProvider.PROVIDER_LOCAL))
        else ActiveModel.Unavailable("${local.selected.displayName} isn't downloaded yet. You can get it in the app.")
      ModelProvider.PROVIDER_OPENAI -> {
        val key = secrets.get(Secret.OPENAI_KEY) ?: return ActiveModel.Unavailable("Add your OpenAI API key in settings first.")
        ActiveModel.TurnBased(OpenAiChatModel(key, s.effectiveOpenAiModel, s.effectiveOpenAiReasoning), "OpenAI")
      }
      ModelProvider.PROVIDER_OPENAI_REALTIME -> {
        val key = secrets.get(Secret.OPENAI_KEY) ?: return ActiveModel.Unavailable("Add your OpenAI API key in settings first.")
        ActiveModel.Realtime(key)
      }
      else -> {
        val key = secrets.get(Secret.OPENROUTER_KEY) ?: return ActiveModel.Unavailable("Add your OpenRouter API key in settings first.")
        ActiveModel.TurnBased(OpenRouterChatModel(key, openRouterInfo(s), s.effectiveOpenRouterReasoning), "OpenRouter")
      }
    }

  /** The local model, when it's installed, for the fallback setting. */
  fun localFallback(): ChatModel? = local.takeIf { it.isInstalled() }

  private fun openRouterInfo(s: AssistantSettings): OpenRouterModelInfo {
    val id = s.effectiveOpenRouterModel
    val name =
      if (id == SettingsDefaults.OPENROUTER_MODEL) SettingsDefaults.OPENROUTER_MODEL_NAME
      else s.openrouterModelName.ifBlank { id }
    return OpenRouterModelInfo(id, name, s.openRouterAcceptsAudio, supportsTools = true, supportsReasoning = true, hasNativeWebSearch = s.openRouterHasNativeSearch)
  }

  /**
   * Looks up [modelId] on OpenRouter (input modalities, native web search) and caches it in
   * settings. Returns null when OpenRouter can't be reached or doesn't list the model.
   */
  suspend fun refreshOpenRouterInfo(modelId: String): OpenRouterModelInfo? =
    withContext(Dispatchers.IO) {
      runCatching {
          val models =
            sharedHttpClient.newCall(Request.Builder().url(OpenRouterCatalog.modelsUrl()).build()).execute().use {
              OpenRouterCatalog.parseModels(it.body.string())
            }
          val base = models.firstOrNull { it.id == modelId } ?: return@runCatching null
          val native =
            sharedHttpClient.newCall(Request.Builder().url(OpenRouterCatalog.endpointsUrl(modelId)).build()).execute().use {
              OpenRouterCatalog.parseNativeWebSearch(it.body.string())
            }
          base.copy(hasNativeWebSearch = native).also { info ->
            settings.update {
              it.setOpenrouterModel(modelId)
                .setOpenrouterModelName(info.name)
                .setOpenrouterModelAcceptsAudio(info.acceptsAudio)
                .setOpenrouterModelNativeSearch(info.hasNativeWebSearch)
                .setOpenrouterModelInfoCached(true)
            }
          }
        }
        .onFailure { Log.w(TAG, "OpenRouter lookup failed", it) }
        .getOrNull()
    }

  /** OpenRouter's tool-capable models, for the model picker. */
  suspend fun openRouterModels(): List<OpenRouterModelInfo> =
    withContext(Dispatchers.IO) {
      sharedHttpClient.newCall(Request.Builder().url(OpenRouterCatalog.modelsUrl()).build()).execute().use {
        OpenRouterCatalog.parseModels(it.body.string())
      }
    }
}
