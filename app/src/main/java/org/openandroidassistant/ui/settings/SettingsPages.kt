/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.openandroidassistant.BuildConfig
import org.openandroidassistant.data.model.DownloadState
import org.openandroidassistant.data.settings.Secret
import org.openandroidassistant.data.settings.SettingsDefaults
import org.openandroidassistant.data.settings.chosenWebSearchMode
import org.openandroidassistant.data.settings.decodeHeaders
import org.openandroidassistant.data.settings.effectiveContinueWithinMs
import org.openandroidassistant.data.settings.effectiveMessageWording
import org.openandroidassistant.data.settings.effectivePhoneNumbers
import org.openandroidassistant.data.settings.effectivePlacesProvider
import org.openandroidassistant.data.settings.effectiveProvider
import org.openandroidassistant.data.settings.effectiveReadPageProvider
import org.openandroidassistant.data.settings.effectiveRealtimeCloseAfterMs
import org.openandroidassistant.data.settings.effectiveTtsPlaybackSpeed
import org.openandroidassistant.data.settings.effectiveTtsProvider
import org.openandroidassistant.data.settings.instanceUrlProblem
import org.openandroidassistant.data.settings.openRouterAcceptsAudio
import org.openandroidassistant.data.settings.openRouterHasNativeSearch
import org.openandroidassistant.proto.MessageWording
import org.openandroidassistant.proto.HistoryMode
import org.openandroidassistant.proto.ModelProvider
import org.openandroidassistant.proto.PhoneNumberSpeech
import org.openandroidassistant.proto.PlacesProvider
import org.openandroidassistant.proto.ReadPageProvider
import org.openandroidassistant.proto.TtsProviderType
import org.openandroidassistant.proto.UnitSystem
import org.openandroidassistant.proto.WebSearchMode
import org.openandroidassistant.ui.components.Choice
import org.openandroidassistant.ui.components.ChoiceGroup
import org.openandroidassistant.ui.components.DownloadRow
import org.openandroidassistant.ui.components.ExampleBox
import org.openandroidassistant.ui.components.HeaderListRow
import org.openandroidassistant.ui.components.Explanation
import org.openandroidassistant.ui.components.LoginRow
import org.openandroidassistant.ui.components.NavRow
import org.openandroidassistant.ui.components.SecretRow
import org.openandroidassistant.ui.components.SectionHeader
import org.openandroidassistant.ui.components.SegmentedChoice
import org.openandroidassistant.ui.components.SettingsPage
import org.openandroidassistant.ui.components.SwitchRow
import org.openandroidassistant.ui.components.TextRow

/** The settings home: one row per page. */
@Composable
fun SettingsHome(vm: SettingsViewModel, onBack: () -> Unit, open: (String) -> Unit) {
  val s by vm.settings.collectAsState()
  SettingsPage("Settings", onBack) {
    NavRow("Model", modelSummary(s), Icons.Filled.Memory) { open("model") }
    NavRow("About you", s.aboutName.ifBlank { "Name, home, work, and anything else" }, Icons.Filled.Face) { open("about") }
    NavRow("Behavior", "How the assistant words messages and reads numbers", Icons.Filled.Tune) { open("behavior") }
    NavRow("Tools", "What the assistant may do, when it asks first, and permissions", Icons.Filled.Widgets) { open("tools") }
    NavRow("Web and places", "Search, reading pages, finding places", Icons.Filled.Language) { open("web") }
    NavRow("Speech", "Voice, speed, and speech recognition", Icons.Filled.RecordVoiceOver) { open("speech") }
    NavRow("Conversations", "Continuing, history", Icons.AutoMirrored.Filled.Chat) { open("conversations") }
    NavRow("Wake word", if (s.librewakewordLinked) "LibreWakeWord" else "Set up LibreWakeWord", Icons.Filled.Hearing) { open("wakeword") }
    NavRow("About", "Version ${BuildConfig.VERSION_NAME} · AGPL-3.0", Icons.Filled.Info) { open("about-app") }
  }
}

private fun modelSummary(s: org.openandroidassistant.proto.AssistantSettings): String =
  when (s.effectiveProvider) {
    ModelProvider.PROVIDER_LOCAL -> "On device · " + org.openandroidassistant.data.model.LocalModel.byId(s.localModel).displayName
    ModelProvider.PROVIDER_OPENAI -> "OpenAI · ${s.openaiModel.ifBlank { SettingsDefaults.OPENAI_MODEL }}"
    ModelProvider.PROVIDER_OPENAI_REALTIME -> "GPT-Live"
    else -> "OpenRouter · ${s.openrouterModelName.ifBlank { s.openrouterModel.ifBlank { SettingsDefaults.OPENROUTER_MODEL_NAME } }}"
  }

private val REASONING = listOf("minimal", "low", "medium", "high", "xhigh")

@Composable
fun ModelSettings(vm: SettingsViewModel, onBack: () -> Unit, openPicker: () -> Unit) {
  val s by vm.settings.collectAsState()
  val secrets by vm.secrets.collectAsState()
  val local by vm.localModel.collectAsState()
  SettingsPage("Model", onBack) {
    Explanation("The assistant runs on one model at a time. Speech-to-text and text-to-speech always run on your phone.")
    ChoiceGroup(
      choices =
        listOf(
          Choice(ModelProvider.PROVIDER_OPENROUTER, "OpenRouter", "Hundreds of cloud models with one key"),
          Choice(ModelProvider.PROVIDER_OPENAI, "OpenAI", "OpenAI's models with your OpenAI key"),
          Choice(ModelProvider.PROVIDER_OPENAI_REALTIME, "GPT-Live", "A live voice you can talk over; needs an OpenAI key"),
          Choice(ModelProvider.PROVIDER_LOCAL, "On device", "Private and works offline; slower and simpler"),
        ),
      selected = s.effectiveProvider,
      onSelect = { p -> vm.update { it.setProvider(p) } },
    )
    when (s.effectiveProvider) {
      ModelProvider.PROVIDER_OPENROUTER -> {
        SectionHeader("OpenRouter")
        SecretRow("API key", secrets[Secret.OPENROUTER_KEY], help = "From openrouter.ai/keys.") { vm.setSecret(Secret.OPENROUTER_KEY, it) }
        val name = s.openrouterModelName.ifBlank { s.openrouterModel.ifBlank { SettingsDefaults.OPENROUTER_MODEL_NAME } }
        val hears = if (s.openRouterAcceptsAudio) "Understands audio natively" else "Uses speech transcription"
        NavRow("Model", "$name · $hears", onClick = openPicker)
        ReasoningChoice(s.openrouterReasoning.ifBlank { SettingsDefaults.OPENROUTER_REASONING }) { r -> vm.update { it.setOpenrouterReasoning(r) } }
      }
      ModelProvider.PROVIDER_OPENAI,
      ModelProvider.PROVIDER_OPENAI_REALTIME -> {
        SectionHeader("OpenAI")
        SecretRow("API key", secrets[Secret.OPENAI_KEY], help = "From platform.openai.com.") { vm.setSecret(Secret.OPENAI_KEY, it) }
        if (s.effectiveProvider == ModelProvider.PROVIDER_OPENAI) {
          TextRow("Model", s.openaiModel.ifBlank { SettingsDefaults.OPENAI_MODEL }) { v -> vm.update { it.setOpenaiModel(v) } }
          Explanation("OpenAI models read your words: your phone turns your speech into text first.")
          ReasoningChoice(s.openaiReasoning.ifBlank { SettingsDefaults.OPENAI_REASONING }) { r -> vm.update { it.setOpenaiReasoning(r) } }
        } else {
          TextRow("Backend model", s.realtimeBackendModel.ifBlank { SettingsDefaults.REALTIME_BACKEND_MODEL }) { v -> vm.update { it.setRealtimeBackendModel(v) } }
          ReasoningChoice(s.realtimeBackendReasoning.ifBlank { SettingsDefaults.REALTIME_BACKEND_REASONING }) { r -> vm.update { it.setRealtimeBackendReasoning(r) } }
          val seconds = (s.effectiveRealtimeCloseAfterMs / 1000).toInt()
          SectionHeader("Ending a conversation")
          Explanation("GPT-Live can't end a conversation itself, so the app closes it after $seconds seconds of quiet once the assistant stops talking.")
          Slider(
            value = seconds.toFloat(),
            onValueChange = { v -> vm.update { it.setRealtimeCloseAfterSeconds(v.toInt()) } },
            valueRange = 2f..15f,
            steps = 12,
            modifier = Modifier.padding(horizontal = 24.dp),
          )
        }
      }
      else -> {
        SectionHeader("On-device model")
        ChoiceGroup(
          org.openandroidassistant.data.model.LocalModel.entries.map { Choice(it.id, it.displayName, it.summary) },
          org.openandroidassistant.data.model.LocalModel.byId(s.localModel).id,
        ) { id -> vm.update { it.setLocalModel(id) } }
        LocalModelRow(local) { vm.download("llm") }
        SwitchRow("Load at startup", "Keeps the model in memory so it answers sooner, using memory other apps could have.", s.loadModelOnBoot) { v ->
          vm.update { it.setLoadModelOnBoot(v) }
        }
      }
    }
    if (s.effectiveProvider != ModelProvider.PROVIDER_LOCAL) {
      SectionHeader("When the cloud can't be reached")
      SwitchRow(
        "Use the on-device model instead",
        if (local is DownloadState.Installed) "The assistant says it switched." else "Download the on-device model first.",
        s.localFallbackEnabled,
        enabled = local is DownloadState.Installed,
      ) { v -> vm.update { it.setLocalFallbackEnabled(v) } }
    }
  }
}

@Composable
private fun ReasoningChoice(current: String, onSelect: (String) -> Unit) {
  SectionHeader("Reasoning effort")
  Explanation("More reasoning handles harder requests but answers slower.")
  SegmentedChoice(REASONING.map { it to it.replaceFirstChar { c -> c.uppercase() }.replace("Xhigh", "Max") }, current, onSelect)
}

@Composable
private fun LocalModelRow(state: DownloadState, onDownload: () -> Unit) =
  DownloadRow(state, downloadLabel = "Download", installedText = "Downloaded.", onDownload = onDownload)

@Composable
fun OpenRouterModelPicker(vm: SettingsViewModel, onBack: () -> Unit) {
  val models by vm.openRouterModels.collectAsState()
  val error by vm.modelListError.collectAsState()
  val s by vm.settings.collectAsState()
  var query by remember { mutableStateOf("") }
  androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadOpenRouterModels() }
  SettingsPage("Choose a model", onBack) {
    androidx.compose.material3.OutlinedTextField(
      value = query,
      onValueChange = { query = it },
      placeholder = { Text("Search models") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
    Explanation("Models that hear your voice avoid speech-recognition mistakes. The others read your words, which your phone turns into text first.")
    error?.let { Explanation(it) }
    val current = s.openrouterModel.ifBlank { SettingsDefaults.OPENROUTER_MODEL }
    val list = models
    if (list == null) {
      LinearProgressIndicator(Modifier.fillMaxWidth().padding(24.dp))
    } else {
      val filtered =
        list.filter { query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true) }
          .sortedWith(compareByDescending<org.openandroidassistant.model.OpenRouterModelInfo> { it.id == current }.thenByDescending { it.acceptsAudio })
      ChoiceGroup(
        choices = filtered.take(200).map { Choice(it.id, it.name, (if (it.acceptsAudio) "Understands audio natively · " else "Uses speech transcription · ") + it.id) },
        selected = current,
        onSelect = { vm.selectOpenRouterModel(it); onBack() },
      )
    }
  }
}

@Composable
fun AboutYouSettings(vm: SettingsViewModel, onBack: () -> Unit) {
  val s by vm.settings.collectAsState()
  SettingsPage("About you", onBack) {
    Explanation("Optional. What you add here is given to the model in every conversation, so it can say \"directions home\" or \"text my wife\".")
    TextRow("Name", s.aboutName) { v -> vm.update { it.setAboutName(v) } }
    TextRow("Home address", s.aboutHome, help = "Also used as your location when Tools, Location is set to your home address.") { vm.setHome(it) }
    TextRow("Work address", s.aboutWork) { v -> vm.update { it.setAboutWork(v) } }
    TextRow(
      "Anything else",
      s.aboutNotes,
      placeholder = "Relationships, languages, preferences…",
      singleLine = false,
      help = "One fact per line, like \"My wife is Jane Doe\" or \"I'm vegetarian\".",
    ) { v -> vm.update { it.setAboutNotes(v) } }
    SectionHeader("Units")
    ChoiceGroup(
      listOf(Choice(UnitSystem.UNITS_UNSPECIFIED, "Automatic", "Follow your phone's region"), Choice(UnitSystem.UNITS_METRIC, "Metric", "°C, kilometers"), Choice(UnitSystem.UNITS_IMPERIAL, "Imperial", "°F, miles")),
      if (s.units == UnitSystem.UNRECOGNIZED) UnitSystem.UNITS_UNSPECIFIED else s.units,
    ) { u -> vm.update { it.setUnits(u) } }
  }
}

@Composable
fun BehaviorSettings(vm: SettingsViewModel, onBack: () -> Unit) {
  val s by vm.settings.collectAsState()
  SettingsPage("Behavior", onBack) {
    SectionHeader("Message wording")
    Explanation("When you ask the assistant to send a message, what should it send? The approval card always shows the final text.")
    ExampleBox("You say", "\"Text Harold telling him I'm gonna be late\"")
    ChoiceGroup(
      listOf(
        Choice(MessageWording.WORDING_EXACT, "Exactly what you said", "I'm gonna be late"),
        Choice(MessageWording.WORDING_LIGHT_CLEANUP, "Light cleanup", "I'm gonna be late."),
        Choice(MessageWording.WORDING_NATURAL, "Written naturally", "Hey Harold, I'm going to be a little late!"),
      ),
      s.effectiveMessageWording,
    ) { w -> vm.update { it.setMessageWording(w) } }
    SectionHeader("Phone numbers")
    ExampleBox("You say", "\"What's the number for Maple Street Dental?\"")
    ChoiceGroup(
      listOf(
        Choice(PhoneNumberSpeech.PHONE_NUMBERS_SPEAK, "Say the digits", "\"It's 555 0142.\" The contact card shows too."),
        Choice(PhoneNumberSpeech.PHONE_NUMBERS_SHOW_ONLY, "Just show it", "\"Here's their number.\" and the contact card."),
      ),
      s.effectivePhoneNumbers,
    ) { p -> vm.update { it.setPhoneNumbers(p) } }
  }
}

@Composable
fun WebSettings(vm: SettingsViewModel, onBack: () -> Unit) {
  val s by vm.settings.collectAsState()
  val secrets by vm.secrets.collectAsState()
  val searxngCheck by vm.searxngCheck.collectAsState()
  SettingsPage("Web and places", onBack) {
    SectionHeader("Web search")
    val provider = s.effectiveProvider
    val nativeAvailable =
      when (provider) {
        ModelProvider.PROVIDER_OPENROUTER -> s.openRouterHasNativeSearch
        ModelProvider.PROVIDER_LOCAL -> false
        else -> true
      }
    val chosen = s.chosenWebSearchMode()
    Explanation(
      when (provider) {
        ModelProvider.PROVIDER_OPENROUTER ->
          if (nativeAvailable) "Your model can search the web itself, or use SearXNG or Tavily." else "Your model can't search the web itself on OpenRouter. SearXNG or Tavily gives it a search."
        ModelProvider.PROVIDER_LOCAL -> "The on-device model can search through SearXNG or Tavily."
        else -> "OpenAI models can search the web themselves, or use SearXNG or Tavily."
      }
    )
    val choices = buildList {
      add(Choice(WebSearchMode.WEB_SEARCH_OFF, "Off"))
      if (nativeAvailable) add(Choice(WebSearchMode.WEB_SEARCH_NATIVE, "The model's own search", "Billed by your model provider"))
      add(Choice(WebSearchMode.WEB_SEARCH_SEARXNG, "SearXNG", "An instance you run"))
      add(Choice(WebSearchMode.WEB_SEARCH_TAVILY, "Tavily", "Free key, 1,000 searches a month"))
    }
    ChoiceGroup(choices, chosen) { m ->
      vm.update {
        when (provider) {
          ModelProvider.PROVIDER_OPENROUTER -> it.setOpenrouterWebSearch(m)
          ModelProvider.PROVIDER_OPENAI, ModelProvider.PROVIDER_OPENAI_REALTIME -> it.setOpenaiWebSearch(m)
          else -> it.setLocalWebSearch(m)
        }
      }
    }
    when (chosen) {
      WebSearchMode.WEB_SEARCH_SEARXNG -> {
        TextRow(
          "SearXNG instance",
          s.searxngUrl,
          placeholder = "https://search.example.org",
          keyboardType = KeyboardType.Uri,
          help = "Public instances turn off the JSON results the assistant needs, so use your own with the json format enabled.",
          validate = ::instanceUrlProblem,
        ) { vm.setSearxngUrl(it) }
        searxngCheck?.let { check ->
          Text(
            check.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (check.problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
          )
        }
        LoginRow("Basic auth", secrets[Secret.SEARXNG_USERNAME], secrets[Secret.SEARXNG_PASSWORD]) { user, pass -> vm.setSearxngLogin(user, pass) }
        HeaderListRow("Extra headers", decodeHeaders(secrets[Secret.SEARXNG_HEADERS])) { vm.setSearxngHeaders(it) }
      }
      WebSearchMode.WEB_SEARCH_TAVILY ->
        SecretRow("Tavily API key", secrets[Secret.TAVILY_KEY], help = "Free at app.tavily.com, no card needed.") { vm.setSecret(Secret.TAVILY_KEY, it) }
      else -> {}
    }
    SectionHeader("Reading pages")
    ChoiceGroup(
      listOf(
        Choice(ReadPageProvider.READ_PAGE_JINA, "Jina Reader", "Works without a key"),
        Choice(ReadPageProvider.READ_PAGE_FIRECRAWL, "Firecrawl", "Your own instance or a Firecrawl key"),
      ),
      s.effectiveReadPageProvider,
    ) { p -> vm.update { it.setReadPageProvider(p) } }
    if (s.effectiveReadPageProvider == ReadPageProvider.READ_PAGE_FIRECRAWL) {
      TextRow("Firecrawl instance", s.firecrawlUrl, placeholder = "Firecrawl cloud", keyboardType = KeyboardType.Uri, validate = ::instanceUrlProblem) { v -> vm.update { it.setFirecrawlUrl(v) } }
      SecretRow("Firecrawl API key", secrets[Secret.FIRECRAWL_KEY]) { vm.setSecret(Secret.FIRECRAWL_KEY, it) }
    } else {
      TextRow("Jina Reader instance", s.jinaUrl, placeholder = SettingsDefaults.JINA_URL, keyboardType = KeyboardType.Uri, validate = ::instanceUrlProblem) { v -> vm.update { it.setJinaUrl(v.trim().trimEnd('/')) } }
      SecretRow("Jina API key", secrets[Secret.JINA_KEY], help = "Optional. Without one, Jina reads up to 20 pages a minute; a free key raises that to 500.") { vm.setSecret(Secret.JINA_KEY, it) }
    }
    SectionHeader("Places")
    ChoiceGroup(
      listOf(
        Choice(PlacesProvider.PLACES_OSM, "OpenStreetMap", "Free, no key"),
        Choice(PlacesProvider.PLACES_GOOGLE, "Google Places", "More complete hours and listings; needs your API key"),
      ),
      s.effectivePlacesProvider,
    ) { p -> vm.update { it.setPlacesProvider(p) } }
    if (s.effectivePlacesProvider == PlacesProvider.PLACES_GOOGLE) {
      SecretRow("Google Places API key", secrets[Secret.GOOGLE_PLACES_KEY]) { vm.setSecret(Secret.GOOGLE_PLACES_KEY, it) }
    } else {
      TextRow(
        "Nominatim instance",
        s.nominatimUrl,
        placeholder = SettingsDefaults.NOMINATIM_URL,
        keyboardType = KeyboardType.Uri,
        help = "The OpenStreetMap search service. The public one allows about one search a second.",
        validate = ::instanceUrlProblem,
      ) { v -> vm.update { it.setNominatimUrl(v.trim().trimEnd('/')) } }
    }
  }
}

@Composable
fun SpeechSettings(vm: SettingsViewModel, onBack: () -> Unit) {
  val s by vm.settings.collectAsState()
  val stt by vm.speechModel.collectAsState()
  val kitten by vm.kittenVoice.collectAsState()
  SettingsPage("Speech", onBack) {
    SectionHeader("Voice")
    ChoiceGroup(
      listOf(
        Choice(TtsProviderType.TTS_PROVIDER_ANDROID, "Android text-to-speech", "Your phone's voice engine"),
        Choice(TtsProviderType.TTS_PROVIDER_KITTEN_NANO, "KittenTTS", "A more natural voice that runs on your phone, English only"),
      ),
      s.effectiveTtsProvider,
    ) { p -> vm.update { it.setTtsProvider(p) } }
    if (s.effectiveTtsProvider == TtsProviderType.TTS_PROVIDER_KITTEN_NANO) {
      if (kitten !is DownloadState.Installed) Explanation("Until it's downloaded, the assistant uses Android text-to-speech.")
      DownloadRow(kitten, downloadLabel = "Download KittenTTS (82 MB)", installedText = "KittenTTS is downloaded.") { vm.download("kitten") }
    }
    SectionHeader("Speed")
    val speed = s.effectiveTtsPlaybackSpeed
    Explanation("%.1f×".format(speed))
    Slider(value = speed, onValueChange = { v -> vm.update { it.setTtsPlaybackSpeed((v * 10).toInt() / 10f) } }, valueRange = 0.7f..2f, modifier = Modifier.padding(horizontal = 24.dp))
    SwitchRow("Light up the reply as it's spoken", checked = !s.speechHighlightingDisabled) { v -> vm.update { it.setSpeechHighlightingDisabled(!v) } }
    SectionHeader("Speech recognition")
    Explanation("Runs on your phone. It hears your yes or no for approvals, and turns what you say into text for models that use speech transcription, and for your history.")
    DownloadRow(stt, downloadLabel = "Download (671 MB)", installedText = "Downloaded.") { vm.download("stt") }
  }
}

@Composable
fun ConversationSettings(vm: SettingsViewModel, onBack: () -> Unit, openHistory: () -> Unit) {
  val s by vm.settings.collectAsState()
  var confirmClear by remember { mutableStateOf(false) }
  SettingsPage("Conversations", onBack) {
    SectionHeader("Continuing")
    val minutes = (s.effectiveContinueWithinMs / 60_000).toInt()
    Explanation("Asking again within $minutes minutes continues the same conversation, so the assistant remembers what you were talking about.")
    Slider(value = minutes.toFloat(), onValueChange = { v -> vm.update { it.setContinueWithinMinutes(v.toInt()) } }, valueRange = 1f..30f, steps = 28, modifier = Modifier.padding(horizontal = 24.dp))
    SectionHeader("History")
    Explanation("Saved conversations stay on this phone, so you can look back at them.")
    ChoiceGroup(
      choices =
        listOf(
          Choice(HistoryMode.HISTORY_TEXT_AND_VOICE, "Text and your voice", "What you said and the replies, with a recording of your voice to play back."),
          Choice(HistoryMode.HISTORY_TEXT_ONLY, "Text only", "What you said and the replies. Recordings already saved stay until you delete them."),
          Choice(HistoryMode.HISTORY_OFF, "Off", "Nothing new is saved."),
        ),
      selected = s.history,
      onSelect = { vm.setHistoryMode(it) },
    )
    NavRow("View history", onClick = openHistory)
    Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = { confirmClear = true }) { Text("Clear history") }
    }
    if (confirmClear) {
      androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear history?") },
        text = { Text("This deletes every saved conversation on this phone.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
      )
    }
  }
}

@Composable
fun AboutAppSettings(onBack: () -> Unit) {
  SettingsPage("About", onBack) {
    Explanation("OpenAndroidAssistant ${BuildConfig.VERSION_NAME}")
    Explanation("Free software under the GNU Affero General Public License, version 3.")
    Text(
      "On-device models keep their own licenses, such as the Gemma Terms of Use. Speech recognition uses NVIDIA Parakeet TDT 0.6B v3 (CC-BY-4.0) and the optional voice uses KittenTTS, both through sherpa-onnx.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
  }
}
