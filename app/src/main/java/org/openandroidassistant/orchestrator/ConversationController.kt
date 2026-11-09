/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.orchestrator

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.openandroidassistant.BuildConfig
import org.openandroidassistant.audio.AudioBus
import org.openandroidassistant.audio.Earcons
import org.openandroidassistant.audio.EndReason
import org.openandroidassistant.audio.EndpointConfig
import org.openandroidassistant.audio.EndpointEvent
import org.openandroidassistant.audio.Endpointer
import org.openandroidassistant.audio.EnergySpeechDetector
import org.openandroidassistant.audio.PacedPcmStream
import org.openandroidassistant.audio.SAMPLE_RATE
import org.openandroidassistant.audio.SileroSpeechDetector
import org.openandroidassistant.audio.SpeechDetector
import org.openandroidassistant.audio.SpeechNormalizer
import org.openandroidassistant.audio.TtsSpeaker
import org.openandroidassistant.audio.rms
import org.openandroidassistant.data.HistoryRecorder
import org.openandroidassistant.data.settings.SettingsRepository
import org.openandroidassistant.data.settings.allowedWhenLocked
import org.openandroidassistant.data.settings.approvalLevel
import org.openandroidassistant.data.settings.effectiveContinueWithinMs
import org.openandroidassistant.data.settings.effectiveProvider
import org.openandroidassistant.data.settings.effectiveRealtimeBackendModel
import org.openandroidassistant.data.settings.effectiveRealtimeBackendReasoning
import org.openandroidassistant.data.settings.effectiveRealtimeCloseAfterMs
import org.openandroidassistant.data.settings.effectiveSpeechHighlightingEnabled
import org.openandroidassistant.data.settings.effectiveTtsProvider
import org.openandroidassistant.data.settings.webSearchMode
import org.openandroidassistant.location.AppLocationProvider
import org.openandroidassistant.model.ActiveModel
import org.openandroidassistant.model.ChatModel
import org.openandroidassistant.model.ChatSession
import org.openandroidassistant.model.ModelError
import org.openandroidassistant.model.ModelKind
import org.openandroidassistant.model.ModelRouter
import org.openandroidassistant.model.ModelToolCall
import org.openandroidassistant.model.ToolResult
import org.openandroidassistant.model.UserContent
import org.openandroidassistant.overlay.RendererRegistry
import org.openandroidassistant.proto.AssistantSettings
import org.openandroidassistant.proto.TtsProviderType
import org.openandroidassistant.proto.WebSearchMode
import org.openandroidassistant.runtime.AudioSegment
import org.openandroidassistant.runtime.LoadState
import org.openandroidassistant.stt.SttSessionFactory
import org.openandroidassistant.stt.asSentence
import org.openandroidassistant.tools.ApprovalLevel
import org.openandroidassistant.tools.AssistantTool
import org.openandroidassistant.tools.Handoff
import org.openandroidassistant.tools.ToolContext
import org.openandroidassistant.tools.ToolEnvironment
import org.openandroidassistant.tools.ToolRegistry
import org.openandroidassistant.tools.ValidatedCall
import org.openandroidassistant.tools.impl.WebSearchTool
import org.openandroidassistant.tools.impl.googleMapsInstalled

private const val TAG = "ConversationController"
private const val PERF_TAG = "OaaPerf"

/** The overlay hides after this long with nothing happening; the conversation can continue. */
private const val IDLE_HIDE_MS = 12_000L
/** Trailing silence that ends a turn. */
private const val TURN_SILENCE_MS = 800L
/** Give up listening when nobody starts speaking. */
private const val NO_SPEECH_MS = 8_000L
private const val MAX_TURN_MS = 120_000L
/** Keep a little silence after speech so endings aren't clipped. */
private const val RETAINED_TAIL_MS = 200L
/** After the approval prompt stops playing, its echo is still filtered this long. */
private const val PROMPT_ECHO_TAIL_MS = 600L
/** Voice approval: decline if nothing is said in this long after the prompt. */
private const val APPROVAL_LISTEN_MS = 8_000L
/** Any approval (including tap-only levels) declines after this long. */
private const val APPROVAL_TIMEOUT_MS = 30_000L
private const val UNLOCK_TIMEOUT_MS = 30_000L
private const val WAVEFORM_POINTS = 48

/**
 * The turn-based assistant: owns the conversation, the mic, speech,
 * approvals and the overlay state. The model only ever proposes; everything it does goes
 * through the [TurnLoop] here.
 */
@Singleton
class ConversationController
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val audioBus: AudioBus,
  private val earcons: Earcons,
  private val tts: TtsSpeaker,
  private val normalizer: SpeechNormalizer,
  private val router: ModelRouter,
  private val registry: ToolRegistry,
  private val settingsRepository: SettingsRepository,
  private val sttSessions: SttSessionFactory,
  private val history: HistoryRecorder,
  private val location: AppLocationProvider,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val executor = ToolExecutor()
  private val ids = AtomicLong(1)

  private val _uiState = MutableStateFlow(OverlayUiState())
  val uiState: StateFlow<OverlayUiState> = _uiState

  /** The overlay sets this while it's showing, for Critical approvals. */
  @Volatile var biometricPrompt: (suspend (title: String) -> Boolean)? = null

  private val speech =
    SpeechQueue(
      scope = scope,
      tts = tts,
      normalizer = normalizer,
      locale = { normalizerLocale },
      highlight = { highlightSpeech },
      onReveal = ::onReveal,
    )
  @Volatile private var normalizerLocale = "en"
  @Volatile private var highlightSpeech = true

  private var conv: Conv? = null
  private var realtime: RealtimeConversation? = null
  /** The last realtime conversation's text and when it ended, to continue it. */
  private var realtimeHistory: Pair<Long, List<Pair<String, String>>>? = null
  private var job: Job? = null
  private var idleJob: Job? = null
  private var pendingApproval: CompletableDeferred<Boolean>? = null
  @Volatile private var pendingUnlock: CompletableDeferred<Boolean>? = null
  /** Set while listening; completing it ends the turn now (tap on the waveform). */
  @Volatile private var endTurnNow: CompletableDeferred<Unit>? = null
  @Volatile private var interruptedNote: String? = null

  private val keyguard = context.getSystemService(KeyguardManager::class.java)

  private class Conv(
    val model: ChatModel,
    val providerName: String,
    val session: ChatSession,
    val loop: TurnLoop,
    val settings: AssistantSettings,
    val historyId: Long?,
  ) {
    val kind: ModelKind
      get() = model.kind

    var lastActiveAt = SystemClock.elapsedRealtime()
  }

  init {
    scope.launch {
      router.local.loadState.collect { state ->
        if (state is LoadState.Ready) update { if (it.phase == AssistantPhase.WAKING_UP) it.copy(phase = AssistantPhase.THINKING) else it }
      }
    }
  }

  // ------------------------------------------------------------------ public surface

  /** Wake word, tile, launcher or voice command. Interrupts anything in progress. */
  fun onTrigger(source: TriggerSource) {
    Log.i(PERF_TAG, "turn_trigger source=$source")
    interrupt("The user started a new request.")
    startListening(prewarm = true)
  }

  /** The mic button: ends the turn while listening; otherwise interrupts and listens. */
  fun onMicPressed() {
    onOverlayInteraction()
    realtime?.let {
      it.toggleMute()
      return
    }
    val phase = _uiState.value.phase
    if (phase == AssistantPhase.LISTENING) {
      endTurnNow?.complete(Unit)
      return
    }
    if (phase != AssistantPhase.IDLE) interrupt("The user tapped the mic to say something else.")
    startListening(prewarm = false)
  }

  /** Stop: silence the assistant and stop work; the conversation stays open. */
  fun onStopPressed() {
    interrupt("The user pressed stop.")
    enterIdle()
  }

  /** Close: the overlay goes away and the conversation ends. */
  fun onOverlayDismissed() {
    realtime?.close()
    interrupt(null)
    earcons.play(Earcons.Cue.DISMISSED)
    endConversation()
    update { OverlayUiState(modelKind = it.modelKind) }
  }

  fun onOverlayInteraction() {
    idleJob?.cancel()
  }

  fun onKeyboardToggled(open: Boolean) {
    onOverlayInteraction()
    if (open) {
      if (_uiState.value.phase == AssistantPhase.LISTENING) {
        job?.cancel()
        enterIdle(hide = false)
      }
      update { it.copy(typing = true, visible = true) }
    } else {
      update { it.copy(typing = false) }
    }
  }

  /** A typed turn. */
  fun onTypedSubmit(text: String) {
    val typed = text.trim()
    if (typed.isEmpty()) return
    realtime?.let {
      update { s -> s.copy(typing = false) }
      it.sendTyped(typed)
      return
    }
    interrupt("The user typed something new.")
    update { it.copy(typing = false, visible = true) }
    job = scope.launch {
      val c = ensureConversation() ?: return@launch
      runTurns(c, Captured(text = typed, typed = true))
    }
  }

  fun onApproveTapped() {
    pendingApproval?.complete(true)
  }

  fun onDeclineTapped() {
    pendingApproval?.complete(false)
  }

  fun onUnlockResult(unlocked: Boolean) {
    pendingUnlock?.complete(unlocked)
  }

  // ------------------------------------------------------------------ debug entry points

  /** Debug builds: a typed turn from adb. */
  fun onDebugText(text: String) {
    if (!BuildConfig.DEBUG) return
    update { it.copy(visible = true) }
    onTypedSubmit(text)
  }

  /**
   * Debug builds: play a 16 kHz mono PCM16 file through the mic pipeline. With [startTurn] it
   * starts listening first (a new request); otherwise it just feeds whatever is listening
   * (answering an approval or a follow-up question).
   */
  fun onDebugAudio(path: String, startTurn: Boolean) {
    if (!BuildConfig.DEBUG) return
    val file = File(path).canonicalFile
    val root = File(context.filesDir, "benchmarks").canonicalFile
    if (!file.path.startsWith(root.path + "/") || !file.isFile) {
      Log.w(TAG, "Debug audio must be a file under $root")
      return
    }
    audioBus.attachHandoff(PacedPcmStream(file))
    if (startTurn) onTrigger(TriggerSource.IN_APP)
  }

  /**
   * Debug builds: speaks [prompt] and runs voice approval over it, with no tool behind it, and logs
   * the verdict. For testing that the prompt's own words (its echo) never approve.
   */
  fun debugApproval(prompt: String, answer: String? = null, answerAtMs: Int = 0) {
    if (!BuildConfig.DEBUG) return
    scope.launch {
      // [answer]: a 16 kHz PCM16 file under files/benchmarks, heard [answerAtMs] after the prompt starts.
      answer?.let { path ->
        val root = File(context.filesDir, "benchmarks").canonicalFile
        val file = File(path).canonicalFile.takeIf { it.path.startsWith(root.path + "/") && it.isFile } ?: return@let
        val padded = File(root, "approval-answer.pcm").apply { writeBytes(ByteArray(answerAtMs * 32) + file.readBytes()) }
        audioBus.attachHandoff(PacedPcmStream(padded))
      }
      promptSentences(prompt).forEach { speech.enqueue(-1, it) }
      val decision = listenForApproval(prompt)
      if (!speech.isIdle) speech.stop()
      audioBus.detachHandoff()
      Log.i(TAG, "debug approval: ${if (decision is ApprovalDecision.Approved) "APPROVED" else "not approved"} prompt=\"$prompt\" answer=$answer at=$answerAtMs")
    }
  }

  /** Debug builds: shows a fixed overlay state for screenshots. */
  fun debugPreview(name: String) {
    if (!BuildConfig.DEBUG) return
    val weather =
      RenderCard.Weather(
        "London, England", "cloudy", "52°", "61°", "48°", "70%",
        listOf(RenderCard.HourForecast("4 PM", "52°", "cloudy", "10%"), RenderCard.HourForecast("5 PM", "51°", "rain", "60%"), RenderCard.HourForecast("6 PM", "50°", "rain", "70%")),
      )
    val base = OverlayUiState(visible = true)
    _uiState.value =
      when (name) {
        "listening_audio" -> base.copy(phase = AssistantPhase.LISTENING, modelKind = ModelKind.AUDIO_IN, micLevel = 0.06f)
        "listening_text" ->
          base.copy(phase = AssistantPhase.LISTENING, modelKind = ModelKind.TEXT_ONLY, micLevel = 0.05f, liveTranscript = "Send a message to Harold telling him I'm gonna")
        "thinking" ->
          base.copy(phase = AssistantPhase.THINKING, workingLabel = "Checking the weather…", exchanges = listOf(Exchange(1, UserEntry.Voice)))
        "speaking" ->
          base.copy(
            phase = AssistantPhase.SPEAKING,
            exchanges = listOf(Exchange(1, UserEntry.Voice, listOf(ExchangePart.Speech(2, "It's 11 degrees and cloudy in London, with rain after three. I'd bring a jacket.", 30), ExchangePart.Card(weather)))),
          )
        "realtime" ->
          base.copy(
            modelKind = ModelKind.REALTIME,
            realtime = RealtimeUi(userLevel = 0.04f, assistantLevel = 0.12f, activity = "Checking your calendar…"),
            exchanges = listOf(Exchange(1, UserEntry.Text("What's on my calendar tomorrow?", typed = false), listOf(ExchangePart.Speech(2, "Let me check.")))),
          )
        "realtime_approval" ->
          base.copy(
            modelKind = ModelKind.REALTIME,
            phase = AssistantPhase.APPROVAL,
            realtime = RealtimeUi(muted = false),
            approval = ApprovalUi(org.openandroidassistant.tools.ApprovalCard("Send message", "Harold Finch", "I'm gonna be late."), ApprovalLevel.LOW, listening = true, realtime = true),
            exchanges = listOf(Exchange(1, UserEntry.Text("Text Harold that I'm gonna be late", typed = false))),
          )
        "markdown" ->
          base.copy(
            exchanges =
              listOf(
                Exchange(
                  1,
                  UserEntry.Text("How do I list files by size in Linux?", typed = true),
                  listOf(ExchangePart.Speech(2, "Here's the command."), ExchangePart.Markdown("```\nls -lhS\n```\n`-S` sorts by size, `-h` shows sizes like 4.0K.")),
                )
              )
          )
        else -> base
      }
  }

  // ------------------------------------------------------------------ conversation lifecycle

  /** Reuses the conversation if it was active within N minutes and nothing changed; else starts one. */
  private suspend fun ensureConversation(): Conv? {
    val settings = settingsRepository.current()
    normalizerLocale =
      if (settings.effectiveTtsProvider == TtsProviderType.TTS_PROVIDER_KITTEN_NANO) "en-US"
      else settings.ttsLanguage.ifEmpty { "en" }
    highlightSpeech = settings.effectiveSpeechHighlightingEnabled
    conv?.let { c ->
      val fresh = SystemClock.elapsedRealtime() - c.lastActiveAt < settings.effectiveContinueWithinMs
      if (fresh && sameModelSettings(c.settings, settings)) return c
      endConversation()
    }
    return when (val active = router.active(settings)) {
      is ActiveModel.Unavailable -> {
        deliverNotice(active.spokenReason)
        null
      }
      is ActiveModel.Realtime -> {
        startRealtime(active.apiKey, settings)
        null
      }
      is ActiveModel.TurnBased ->
        try {
          openConversation(active.model, active.providerName, settings)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // A local model that won't load must not take the app down with it.
          Log.e(TAG, "couldn't open a conversation", e)
          val reason = if (active.model.isLocal) "I couldn't load the on-device model." else "Something went wrong with ${active.providerName}."
          deliverNotice(reason, detail = e.message)
          null
        }
    }
  }

  private suspend fun openConversation(model: ChatModel, providerName: String, settings: AssistantSettings): Conv {
    val env = toolEnvironment(settings)
    val nativeSearch = settings.webSearchMode() == WebSearchMode.WEB_SEARCH_NATIVE && !model.isLocal
    val setup =
      ConversationSpecBuilder.build(
        settings = settings,
        modelKind = model.kind,
        exposed = registry.exposedTools(settings, realtime = false),
        env = env,
        nativeWebSearch = nativeSearch,
        unavailable = unavailable(settings, realtime = false),
      )
    if (model.isLocal && router.local.loadState.value != LoadState.Ready) update { it.copy(phase = AssistantPhase.WAKING_UP) }
    val session = model.openSession(setup.spec)
    val toolContext = ToolContext(context, settings, HandleTable(), location)
    val loop =
      TurnLoop(
        session = session,
        modelKind = model.kind,
        tools = setup.tools,
        schemas = setup.schemas,
        executor = executor,
        toolContext = toolContext,
        approvalLevel = { settings.approvalLevel(it) },
        allowedWhenLocked = { settings.allowedWhenLocked(it) },
        blockActions = BuildConfig.DEBUG && systemProperty("debug.oaa.block_actions") == "on",
      )
    Log.i(TAG, "conversation started: ${model.displayName} (${model.kind}), ${setup.tools.size} tools")
    return Conv(model, providerName, session, loop, settings, history.startConversation()).also {
      conv = it
      update { s -> s.copy(modelKind = model.kind, exchanges = emptyList()) }
    }
  }

  /** What tool definitions adapt to, read when a conversation starts. */
  private fun toolEnvironment(settings: AssistantSettings) =
    ToolEnvironment(locationAvailable = location.isAvailable(settings), googleMapsInstalled = googleMapsInstalled(context))

  private fun unavailable(settings: AssistantSettings, realtime: Boolean) =
    Unavailable(registry.unavailable(settings, realtime), location.unavailableReason(settings))

  private fun sameModelSettings(a: AssistantSettings, b: AssistantSettings): Boolean =
    a.toBuilder().clearToolPrefs().clearRequestedCapabilityIds().build() ==
      b.toBuilder().clearToolPrefs().clearRequestedCapabilityIds().build() && a.toolPrefsMap == b.toolPrefsMap

  private fun endConversation() {
    val c = conv ?: return
    conv = null
    runCatching { c.session.close() }
    if (c.model.isLocal) scope.launch { warmLocalModel() }
  }

  /** Prepares the next on-device conversation while idle (see LitertChatModel.warmUp). */
  suspend fun warmLocalModel() {
    val settings = settingsRepository.current()
    if (conv != null || settings.effectiveProvider != org.openandroidassistant.proto.ModelProvider.PROVIDER_LOCAL) return
    router.active(settings) // selects the configured local model
    if (router.local.loadState.value != LoadState.Ready) return
    val setup =
      ConversationSpecBuilder.build(
        settings = settings,
        modelKind = router.local.kind,
        exposed = registry.exposedTools(settings, realtime = false),
        env = toolEnvironment(settings),
        nativeWebSearch = false,
        unavailable = unavailable(settings, realtime = false),
      )
    runCatching { router.local.warmUp(setup.spec) }.onFailure { Log.w(TAG, "warm-up failed", it) }
  }

  // ------------------------------------------------------------------ turns

  /** What one user turn was: audio, a transcript, or typed text. */
  private class Captured(
    val pcm: ShortArray? = null,
    val text: String? = null,
    val typed: Boolean = false,
  )

  /** Tool calls and results per exchange, until the exchange is saved to history. */
  private val toolSteps = HashMap<Long, MutableList<JSONObject>>()

  private fun startListening(prewarm: Boolean) {
    idleJob?.cancel()
    update { it.copy(visible = true, typing = false, notice = null) }
    job =
      scope.launch {
        val settings = settingsRepository.current()
        if (prewarm) {
          launch { runCatching { tts.prewarm() } }
          launch { runCatching { sttSessions.prewarm(settings) } }
        }
        val active = router.active(settings)
        if (active is ActiveModel.Realtime) {
          audioBus.detachHandoff()
          if (realtime == null) startRealtime(active.apiKey, settings)
          return@launch
        }
        if (active is ActiveModel.Unavailable && conv == null) {
          audioBus.detachHandoff()
          deliverNotice(active.spokenReason)
          enterIdle()
          return@launch
        }
        // The conversation (and a local model load) starts while the user speaks.
        val convDeferred = async { ensureConversation() }
        val kind = conv?.kind ?: active.kindOrNull() ?: ModelKind.AUDIO_IN
        val captured = capture(kind) ?: run {
          audioBus.detachHandoff()
          convDeferred.cancel()
          enterIdle()
          return@launch
        }
        audioBus.detachHandoff()
        val c = convDeferred.await() ?: return@launch
        runTurns(c, captured)
      }
  }

  private fun ActiveModel.kindOrNull(): ModelKind? = (this as? ActiveModel.TurnBased)?.model?.kind

  /** Runs a turn, then follow-up turns while the model waits for a reply. */
  private suspend fun runTurns(first: Conv, firstInput: Captured) {
    var c = first
    var input: Captured? = firstInput
    while (input != null) {
      val outcome = runOneTurn(c, input)
      input = null
      when (outcome) {
        is TurnLoop.Outcome.Ended -> {
          speech.awaitIdle()
          c.lastActiveAt = SystemClock.elapsedRealtime()
          when {
            outcome.handoff != null -> {
              handOff(outcome.handoff)
              return
            }
            outcome.waitForReply -> input = capture(c.kind) ?: break
          }
        }
        is TurnLoop.Outcome.Failed -> {
          val fallback = handleFailure(c, outcome.error) ?: break
          c = fallback
          input = firstInput
        }
      }
    }
    enterIdle()
  }

  private suspend fun runOneTurn(c: Conv, captured: Captured): TurnLoop.Outcome {
    val exchangeId = ids.getAndIncrement()
    val userEntry =
      when {
        captured.typed -> UserEntry.Text(captured.text.orEmpty(), typed = true)
        c.kind == ModelKind.AUDIO_IN -> UserEntry.Voice
        else -> UserEntry.Text(captured.text.orEmpty(), typed = false)
      }
    update { it.copy(exchanges = it.exchanges + Exchange(exchangeId, userEntry), liveTranscript = "", waveform = emptyList()) }
    val note = interruptedNote.also { interruptedNote = null }
    val user =
      UserContent(
        audio = if (!captured.typed && c.kind == ModelKind.AUDIO_IN) captured.pcm?.let(AudioSegment::fromShorts) else null,
        text = if (captured.typed || c.kind != ModelKind.AUDIO_IN) captured.text else null,
        context = listOfNotNull(note, currentTimeContext()).joinToString(" "),
      )
    val host = Host(exchangeId).also { it.saved += exchangeId to captured }
    val startedAt = SystemClock.elapsedRealtime()
    val outcome =
      try {
        c.loop.runTurn(host, user)
      } finally {
        Log.i(PERF_TAG, "turn_done elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
        update { it.copy(workingLabel = null) }
        for ((id, said) in host.saved) saveExchange(c, id, said)
      }
    return outcome
  }

  /** Unreachable cloud: say so and stop, or switch to the local model if that's allowed. */
  private suspend fun handleFailure(c: Conv, error: ModelError): Conv? {
    Log.w(TAG, "model error: $error")
    if (error is ModelError.Unreachable) {
      val local = router.localFallback()
      if (c.settings.localFallbackEnabled && local != null && !c.model.isLocal) {
        speech.enqueue(-1, "I can't reach ${c.providerName}, so I'm using the on-device model.")
        endConversation()
        return openConversation(local, router.providerName(org.openandroidassistant.proto.ModelProvider.PROVIDER_LOCAL), c.settings)
      }
      deliverNotice("I can't reach ${c.providerName} right now.")
    } else {
      deliverNotice("Something went wrong with ${c.providerName}.", detail = error.message)
    }
    return null
  }

  private suspend fun handOff(handoff: Handoff) {
    withContext(Dispatchers.Main) { runCatching { context.startActivity(handoff.intent) }.onFailure { Log.w(TAG, "handoff failed", it) } }
    endConversation()
    update { OverlayUiState(modelKind = it.modelKind) }
  }

  private fun saveExchange(c: Conv, exchangeId: Long, captured: Captured) {
    val steps = synchronized(toolSteps) { toolSteps.remove(exchangeId) }.orEmpty()
    val exchange = _uiState.value.exchanges.firstOrNull { it.id == exchangeId } ?: return
    val spoken = exchange.parts.filterIsInstance<ExchangePart.Speech>().joinToString(" ") { it.text }
    val shown =
      exchange.parts.mapNotNull {
        when (it) {
          is ExchangePart.Card -> cardSummary(it.card)
          is ExchangePart.Markdown -> it.text
          else -> null
        }
      }.joinToString("\n")
    history.saveExchange(
      conversationId = c.historyId,
      userKind = if (captured.typed) "typed" else if (c.kind == ModelKind.AUDIO_IN) "voice" else "transcript",
      userText = captured.text.orEmpty(),
      assistantText = spoken,
      shown = shown,
      toolSteps = if (steps.isEmpty()) "" else JSONArray(steps).toString(),
      voicePcm = if (!captured.typed) captured.pcm else null,
    )
  }

  // ------------------------------------------------------------------ capture

  /**
   * Listens for one user turn. Native audio in: audio plus a building waveform. Text-only: a
   * live transcript. Returns null when nobody spoke.
   */
  private suspend fun capture(kind: ModelKind): Captured? {
    val settings = settingsRepository.current()
    val stream = if (kind == ModelKind.TEXT_ONLY) sttSessions.session(settings)?.openStream() else null
    if (kind == ModelKind.TEXT_ONLY && stream == null) {
      deliverNotice("Speech recognition isn't downloaded yet. You can type instead, or get it in the app.")
      return null
    }
    update { it.copy(phase = AssistantPhase.LISTENING, liveTranscript = "", waveform = emptyList(), micLevel = 0f) }
    earcons.play(Earcons.Cue.LISTENING)
    Log.i(PERF_TAG, "listening kind=$kind")
    val endNow = CompletableDeferred<Unit>().also { endTurnNow = it }
    val endpointer = Endpointer(EndpointConfig(silenceTimeoutMs = TURN_SILENCE_MS, noSpeechTimeoutMs = NO_SPEECH_MS, maxTurnMs = MAX_TURN_MS))
    val detector = speechDetector()
    val pcm = ArrayList<Short>(SAMPLE_RATE * 8)
    val wave = ArrayDeque<Float>()
    var reason: EndReason? = null
    var transcript = ""
    val startedAt = SystemClock.elapsedRealtime()
    try {
      withContext(Dispatchers.IO) {
        audioBus.listen { frame ->
          frame.forEach(pcm::add)
          val speaking = detector.isSpeech(frame)
          stream?.let { transcript = it.accept(frame, speaking) }
          if (endNow.isCompleted) {
            reason = EndReason.SILENCE
            return@listen true
          }
          val level = frame.rms()
          wave.addLast((level * 6f).coerceIn(0f, 1f))
          if (wave.size > WAVEFORM_POINTS) wave.removeFirst()
          update { s -> s.copy(micLevel = level, waveform = wave.toList(), liveTranscript = transcript) }
          when (val e = endpointer.onFrame(speaking)) {
            is EndpointEvent.Endpoint -> {
              reason = e.reason
              true
            }
            is EndpointEvent.NoSpeech -> true
            else -> false
          }
        }
      }
    } finally {
      endTurnNow = null
      detector.close()
    }
    if (reason == null || (reason != EndReason.SILENCE && reason != EndReason.MAX_LENGTH)) {
      stream?.close()
      return null
    }
    if (reason == EndReason.SILENCE && !endNow.isCompleted) {
      val trim = ((TURN_SILENCE_MS - RETAINED_TAIL_MS) * SAMPLE_RATE / 1000).toInt().coerceAtMost(pcm.size)
      pcm.subList(pcm.size - trim, pcm.size).clear()
    }
    val finishStart = SystemClock.elapsedRealtime()
    val finalText = stream?.use { it.finish() }?.asSentence()
    if (stream != null) Log.i(PERF_TAG, "stt_final_ms=${SystemClock.elapsedRealtime() - finishStart}")
    if (BuildConfig.DEBUG && finalText != null) Log.d(TAG, "transcript: \"$finalText\"")
    Log.i(PERF_TAG, "capture_done audio_ms=${pcm.size * 1000L / SAMPLE_RATE} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
    earcons.play(Earcons.Cue.ACKNOWLEDGED)
    update { it.copy(phase = AssistantPhase.THINKING, micLevel = 0f) }
    if (kind == ModelKind.TEXT_ONLY && finalText.isNullOrBlank()) return null
    return Captured(pcm = pcm.toShortArray(), text = finalText)
  }

  private fun speechDetector(): SpeechDetector =
    runCatching { SileroSpeechDetector(context.assets) }
      .onFailure { Log.w(TAG, "Silero VAD unavailable; using energy detection", it) }
      .getOrElse { EnergySpeechDetector() }

  // ------------------------------------------------------------------ approval

  /** [voiceModelAsks]: realtime, where GPT-Live says the question instead of local speech. */
  private suspend fun approve(request: ApprovalRequest, voiceModelAsks: Boolean = false): ApprovalDecision {
    update {
      it.copy(
        phase = AssistantPhase.APPROVAL,
        approval = ApprovalUi(request.content.card, request.level, listening = false, realtime = voiceModelAsks),
      )
    }
    val tapped = CompletableDeferred<Boolean>().also { pendingApproval = it }
    try {
      // The prompt plays while the user can already answer, by voice or tap.
      val prompt = request.content.spokenPrompt.takeUnless { voiceModelAsks }
      // Sentence by sentence, so the listener knows which words have been said (and could echo).
      prompt?.let { promptSentences(it).forEach { sentence -> speech.enqueue(-1, sentence) } }
      val decision: ApprovalDecision =
        withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
          if (request.level == ApprovalLevel.LOW) {
            update { s -> s.copy(approval = s.approval?.copy(listening = true)) }
            val voice = async { listenForApproval(prompt) }
            val result =
              kotlinx.coroutines.selects.select<ApprovalDecision?> {
                tapped.onAwait { if (it) ApprovalDecision.Approved else ApprovalDecision.Declined(null, null) }
                voice.onAwait { it }
              }
            voice.cancel()
            result
          } else {
            if (tapped.await()) ApprovalDecision.Approved else ApprovalDecision.Declined(null, null)
          }
        } ?: ApprovalDecision.Declined(null, null)
      if (decision is ApprovalDecision.Approved && request.level == ApprovalLevel.CRITICAL) {
        val prompt = biometricPrompt ?: return ApprovalDecision.Declined(null, null)
        if (!withContext(Dispatchers.Main) { prompt(request.content.card.title) }) return ApprovalDecision.Declined(null, null)
      }
      return decision
    } finally {
      pendingApproval = null
      // Decided while the prompt was still playing: cut it off.
      if (!speech.isIdle) speech.stop()
      update { it.copy(approval = null, phase = AssistantPhase.THINKING) }
    }
  }

  /**
   * The strict voice rule: a clean affirmative plus silence approves; anything
   * else declines at once, and the app keeps listening so what the user said goes to the model.
   */
  /**
   * Listens for yes or no, from the start of [prompt] (the spoken question) when there is one.
   * Echo cancellation lets the mic hear the user over the prompt, but not perfectly: while the
   * prompt plays, a transcript made of the prompt's own words is its echo, and can't approve,
   * decline, or count as the user speaking. If that's all there was when the prompt ends, listening
   * starts over, so the prompt's own "send it?" can never approve.
   */
  private suspend fun listenForApproval(prompt: String? = null): ApprovalDecision {
    val settings = settingsRepository.current()
    val session = sttSessions.session(settings) ?: return ApprovalDecision.Declined(null, null)
    var stream = session.openStream()
    var grammar = ApprovalGrammar()
    Log.i(PERF_TAG, "approval_listening")
    val vad = speechDetector()
    val pcm = ArrayList<Short>(SAMPLE_RATE * 4)
    // Only what the speaker has started saying can come back as echo: a "send it" heard before the
    // prompt reached "Send it?" is the user's.
    val sentences = prompt?.let(::promptSentences).orEmpty().toSet()
    val promptWords = HashSet<String>()
    var lastHeardSentence: String? = null
    fun words(text: String) = ApprovalGrammar.normalize(text).isNotEmpty()
    fun echo(text: String): Boolean {
      val words = ApprovalGrammar.normalize(text)
      return words.isNotEmpty() && words.count { it in promptWords } * 5 >= words.size * 4
    }
    var promptPlaying = prompt != null
    var quietFor = 0L
    var approvedOverPrompt = false
    var speechFrames = 0
    var verdict = ApprovalGrammar.Verdict.UNDECIDED
    var heardSpeech = false
    var silenceMs = 0L
    var elapsed = 0L
    var sincePrompt = 0L
    var transcript = ""
    var declinedAt = -1L
    withContext(Dispatchers.IO) {
      audioBus.listen { frame ->
        elapsed += 20
        speech.lastStarted?.takeIf { it in sentences && it != lastHeardSentence }?.let { sentence ->
          lastHeardSentence = sentence
          promptWords += ApprovalGrammar.normalize(sentence) + ApprovalGrammar.normalize(normalizer.normalize(sentence, "en"))
        }
        quietFor = if (speech.isIdle) quietFor + 20 else 0
        // The speaker drains for a moment after the queue goes idle: its echo can still arrive.
        if (promptPlaying && quietFor >= PROMPT_ECHO_TAIL_MS) {
          promptPlaying = false
          // Nothing decoded yet (the recognizer may still be loading): decode what's there now, so
          // a yes said over the prompt isn't thrown away with its echo.
          if (verdict == ApprovalGrammar.Verdict.UNDECIDED && !words(transcript)) transcript = stream.peek()
          if (verdict == ApprovalGrammar.Verdict.UNDECIDED && (!words(transcript) || echo(transcript))) {
            if (transcript.isNotBlank()) Log.i(TAG, "approval: prompt echo ignored: \"$transcript\"")
            stream.close()
            stream = session.openStream()
            grammar = ApprovalGrammar()
            pcm.clear()
            heardSpeech = false
            silenceMs = 0
            transcript = ""
          }
        }
        if (!promptPlaying) sincePrompt += 20
        frame.forEach(pcm::add)
        val speaking = vad.isSpeech(frame)
        if (speaking) speechFrames++
        transcript = stream.accept(frame, speaking)
        if (speaking) silenceMs = 0 else silenceMs += 20
        // Only words count as the user answering (noise comes out as "uh" or nothing), and they
        // arrive a moment after the voice: a short "yes" is over before its text is.
        val heardEcho = promptPlaying && (!words(transcript) || echo(transcript))
        if (!heardEcho && words(transcript)) heardSpeech = true
        when (verdict) {
          ApprovalGrammar.Verdict.UNDECIDED -> {
            if (heardEcho) return@listen false
            verdict = grammar.update(transcript, if (heardSpeech) silenceMs else 0)
            if (words(transcript) && !speech.isIdle) {
              // The user is answering over the question: stop asking. A yes approves after the
              // usual silence; anything else declines at their pause.
              approvedOverPrompt = true
              speech.stop()
            }
            if (verdict == ApprovalGrammar.Verdict.DECLINE) {
              declinedAt = elapsed
              Log.i(TAG, "approval: declined on \"$transcript\" at ${elapsed} ms, prompt playing=$promptPlaying, quiet for ${quietFor} ms")
              // The user is talking over the question: stop asking.
              if (!speech.isIdle) speech.stop()
              update { s -> s.copy(approval = null, phase = AssistantPhase.LISTENING) }
            }
            when {
              verdict == ApprovalGrammar.Verdict.APPROVE -> true
              // A cough or a word the recognizer couldn't place, then silence: treat as no answer.
              verdict == ApprovalGrammar.Verdict.UNDECIDED && heardSpeech && silenceMs > 2_000 -> true
              !heardSpeech && sincePrompt > APPROVAL_LISTEN_MS -> true
              else -> false
            }
          }
          // Declined: keep listening as a normal turn until the user stops talking.
          else -> silenceMs >= TURN_SILENCE_MS || elapsed - declinedAt > MAX_TURN_MS
        }
      }
    }
    vad.close()
    val finalText = stream.use { it.finish() }
    // The live text is a re-decode of the audio so far. Approving acts on the user's behalf, so
    // the one pass over the whole answer must also be a clean affirmative.
    // A yes said over the prompt can carry some of its echo first ("…your calendar? yes").
    val answer = if (approvedOverPrompt) ApprovalGrammar.normalize(finalText).dropWhile { it in promptWords }.joinToString(" ") else finalText
    if (verdict == ApprovalGrammar.Verdict.APPROVE && !ApprovalGrammar.isAffirmative(finalText) && !ApprovalGrammar.isAffirmative(answer)) verdict = ApprovalGrammar.Verdict.DECLINE
    Log.i(TAG, "approval verdict=$verdict over_prompt=$approvedOverPrompt transcript=\"$finalText\" frames=${elapsed / 20} speech_frames=$speechFrames")
    return when (verdict) {
      ApprovalGrammar.Verdict.APPROVE -> ApprovalDecision.Approved
      ApprovalGrammar.Verdict.DECLINE -> ApprovalDecision.Declined(AudioSegment.fromShorts(pcm.toShortArray()), finalText)
      else -> ApprovalDecision.Declined(null, null)
    }
  }

  private suspend fun unlock(verb: String): Boolean {
    val prompt = "Unlock your phone so I can $verb."
    update { it.copy(unlockPrompt = prompt) }
    val gate = CompletableDeferred<Boolean>().also { pendingUnlock = it }
    try {
      speech.enqueue(-1, prompt)
      withContext(Dispatchers.Main) { KeyguardGateActivity.launch(context) }
      return withTimeoutOrNull(UNLOCK_TIMEOUT_MS) { gate.await() } ?: false
    } finally {
      pendingUnlock = null
      update { it.copy(unlockPrompt = null) }
    }
  }

  // ------------------------------------------------------------------ realtime

  private fun startRealtime(apiKey: String, settings: AssistantSettings) {
    endConversation()
    val env = toolEnvironment(settings)
    val exposed = registry.exposedTools(settings, realtime = true)
    val tools = exposed.associateBy { it.schema(env).name }
    val schemas = tools.mapValues { (_, t) -> t.schema(env) } + (TurnLoop.SHOW_TOOL to ConversationSpecBuilder.showSchema())
    val nativeSearch = settings.webSearchMode(org.openandroidassistant.proto.ModelProvider.PROVIDER_OPENAI_REALTIME) == WebSearchMode.WEB_SEARCH_NATIVE
    val history =
      realtimeHistory?.takeIf { SystemClock.elapsedRealtime() - it.first < settings.effectiveContinueWithinMs }?.second.orEmpty()
    val config =
      org.openandroidassistant.realtime.LiveConfig(
        apiKey = apiKey,
        instructions = ConversationSpecBuilder.liveInstructions(settings),
        history = history,
        backendModel = settings.effectiveRealtimeBackendModel,
        backendReasoning = settings.effectiveRealtimeBackendReasoning,
        backendInstructions =
          ConversationSpecBuilder.liveBackendInstructions(settings, nativeSearch, WebSearchTool.ID in tools, unavailable(settings, realtime = true)),
        tools = schemas.values.toList(),
        webSearch = nativeSearch,
      )
    val conversation =
      RealtimeConversation(
        scope = scope,
        config = config,
        audioBus = audioBus,
        host = realtimeHost,
        tools = tools,
        schemas = schemas,
        toolContext = ToolContext(context, settings, HandleTable(), location),
        approvalLevel = { settings.approvalLevel(it) },
        allowedWhenLocked = { settings.allowedWhenLocked(it) },
        closeAfterMs = settings.effectiveRealtimeCloseAfterMs,
      )
    realtime = conversation
    update { it.copy(exchanges = if (history.isEmpty()) emptyList() else it.exchanges, modelKind = ModelKind.REALTIME) }
    conversation.start()
  }

  private val realtimeHost =
    object : RealtimeHost {
      override fun update(transform: (OverlayUiState) -> OverlayUiState) = _uiState.update(transform)

      override suspend fun approveLocally(request: ApprovalRequest): ApprovalDecision = approve(request, voiceModelAsks = true)

      override val isLocked: Boolean
        get() = keyguard.isKeyguardLocked

      override suspend fun requestUnlock(verb: String): Boolean = unlock(verb)

      override fun handOff(handoff: Handoff) {
        scope.launch { this@ConversationController.handOff(handoff) }
      }

      override fun onEnded(transcript: List<Pair<String, String>>, notice: String?) {
        realtime = null
        realtimeHistory = SystemClock.elapsedRealtime() to transcript
        scope.launch {
          if (notice != null) deliverNotice(notice)
          this@ConversationController.update { it.copy(visible = false, typing = false) }
        }
      }
    }

  // ------------------------------------------------------------------ the turn host

  private inner class Host(private var exchangeId: Long) : TurnHost {
    private var partId: Long? = null
    private var chunker = SentenceChunker()
    /** The exchanges this turn shows, each with what the user said, for history. */
    val saved = mutableListOf<Pair<Long, Captured>>()

    override fun onRunStarted() {
      partId = null
      chunker = SentenceChunker()
      update { if (it.phase == AssistantPhase.WAKING_UP || it.phase == AssistantPhase.APPROVAL) it else it.copy(phase = AssistantPhase.THINKING) }
    }

    override fun onPartialText(text: String) {
      val id = partId ?: ids.getAndIncrement().also {
        partId = it
        addPart(exchangeId, ExchangePart.Speech(it, ""))
      }
      setSpeechText(exchangeId, id, text)
      for ((offset, sentence) in chunker.take(text)) speech.enqueue(id, sentence, offset)
    }

    override fun onMessageText(text: String) {
      if (text.isEmpty()) return
      val id = partId ?: ids.getAndIncrement().also {
        partId = it
        addPart(exchangeId, ExchangePart.Speech(it, ""))
      }
      // The final text may differ from the stream (whitespace trimmed); keep offsets on the stream.
      setSpeechText(exchangeId, id, text)
      for ((offset, sentence) in chunker.take(text)) speech.enqueue(id, sentence, offset)
      chunker.rest(text)?.let { (offset, rest) -> speech.enqueue(id, rest, offset) }
    }

    override fun say(text: String) = speech.enqueue(-1, text)

    override suspend fun awaitSpeechIdle() = speech.awaitIdle()

    override fun onToolStarted(tool: AssistantTool, call: ValidatedCall) {
      update { it.copy(workingLabel = tool.metadata.progressPhrase?.removeSuffix(".")?.plus("…")) }
    }

    override fun onToolFinished(tool: AssistantTool) {
      update { it.copy(workingLabel = null) }
    }

    override fun onToolResult(call: ModelToolCall, result: ToolResult) {
      val step =
        JSONObject()
          .put("name", call.name)
          .put("args", JSONObject(call.args))
          .put("result", runCatching<Any> { JSONObject(result.content) }.getOrElse { result.content })
      synchronized(toolSteps) { toolSteps.getOrPut(exchangeId) { mutableListOf() } += step }
    }

    override fun show(items: List<HandleEntry>, markdown: String?) {
      // A model may show the same thing again in a later run; it appears once.
      val onScreen = _uiState.value.exchanges.firstOrNull { it.id == exchangeId }?.parts.orEmpty().toSet()
      items.mapNotNull(RendererRegistry::render).map(ExchangePart::Card).filter { it !in onScreen }.forEach { addPart(exchangeId, it) }
      markdown?.let(ExchangePart::Markdown)?.takeIf { it !in onScreen }?.let { addPart(exchangeId, it) }
    }

    override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision =
      approve(request).also { decision ->
        // Said something other than yes ("no, make it 1pm"): that's the user talking. It shows as
        // their message, and what the assistant does next goes under it.
        val reply = (decision as? ApprovalDecision.Declined)?.replyTranscript?.takeIf { it.isNotBlank() } ?: return@also
        val id = ids.getAndIncrement()
        update { it.copy(exchanges = it.exchanges + Exchange(id, UserEntry.Text(reply.asSentence(), typed = false))) }
        exchangeId = id
        partId = null
        chunker = SentenceChunker()
        saved += id to Captured(pcm = decision.replyAudio?.pcm?.let(::pcm16ToShorts), text = reply.asSentence())
      }

    override val isLocked: Boolean
      get() = keyguard.isKeyguardLocked

    override suspend fun requestUnlock(verb: String): Boolean = unlock(verb)
  }

  // ------------------------------------------------------------------ state helpers

  /** Cancels the current turn and speech. Completed actions stand. */
  private fun interrupt(note: String?) {
    speech.stop()
    pendingApproval?.complete(false)
    endTurnNow = null
    val running = job
    if (running?.isActive == true && note != null && conv != null) {
      interruptedNote = "(Your previous reply was interrupted: $note)"
    }
    running?.cancel()
    idleJob?.cancel()
  }

  private fun enterIdle(hide: Boolean = true) {
    update { it.copy(phase = AssistantPhase.IDLE, micLevel = 0f, workingLabel = null, approval = null, liveTranscript = "") }
    sttSessions.idle()
    idleJob?.cancel()
    if (!hide) return
    idleJob =
      scope.launch {
        speech.awaitIdle()
        delay(IDLE_HIDE_MS)
        update { it.copy(visible = false, typing = false) }
      }
  }

  private suspend fun deliverNotice(text: String, detail: String? = null) {
    Log.w(TAG, "notice: $text ${detail.orEmpty()}")
    update { it.copy(visible = true, notice = text, phase = AssistantPhase.IDLE) }
    withContext(NonCancellable) { speech.speakAndWait(-1, text) }
  }

  private fun addPart(exchangeId: Long, part: ExchangePart) = update { s ->
    s.copy(exchanges = s.exchanges.map { if (it.id == exchangeId) it.copy(parts = it.parts + part) else it })
  }

  private fun setSpeechText(exchangeId: Long, partId: Long, text: String) = update { s ->
    s.copy(
      exchanges =
        s.exchanges.map { ex ->
          if (ex.id != exchangeId) ex
          else ex.copy(parts = ex.parts.map { p -> if (p is ExchangePart.Speech && p.id == partId) p.copy(text = text) else p })
        }
    )
  }

  private fun onReveal(partId: Long, chars: Int?) {
    if (partId < 0) return
    update { s ->
      val speaking = chars != null
      s.copy(
        phase =
          when {
            speaking && (s.phase == AssistantPhase.THINKING || s.phase == AssistantPhase.IDLE) -> AssistantPhase.SPEAKING
            !speaking && s.phase == AssistantPhase.SPEAKING -> AssistantPhase.THINKING
            else -> s.phase
          },
        exchanges =
          s.exchanges.map { ex ->
            ex.copy(parts = ex.parts.map { p -> if (p is ExchangePart.Speech && p.id == partId) p.copy(revealedChars = chars) else p })
          },
      )
    }
  }

  private inline fun update(transform: (OverlayUiState) -> OverlayUiState) = _uiState.update(transform)
}

internal fun cardSummary(card: RenderCard): String =
  when (card) {
    is RenderCard.Contact -> "Contact: ${card.name}"
    is RenderCard.Message -> "Message: ${card.from}: ${card.body}"
    is RenderCard.Event -> "Event: ${card.title}, ${card.timeLabel}"
    is RenderCard.Weather -> "Weather: ${card.place}, ${card.temperature} ${card.summary}"
    is RenderCard.Alarm -> "Alarm: ${card.timeLabel}"
    is RenderCard.Timer -> "Timer: ${card.label ?: ""}"
    is RenderCard.Reminder -> "Reminder: ${card.text}"
    is RenderCard.Note -> "Note: ${card.title}"
    is RenderCard.Place -> "Place: ${card.name}"
    is RenderCard.Notification -> "Notification: ${card.app}: ${card.title}"
    is RenderCard.NowPlaying -> "Now playing: ${card.title}"
    is RenderCard.WebResult -> "Link: ${card.title}"
    is RenderCard.ActionDone -> card.summary
  }

internal fun systemProperty(name: String): String? =
  try {
    @Suppress("PrivateApi")
    val clazz = Class.forName("android.os.SystemProperties")
    clazz.getMethod("get", String::class.java).invoke(null, name) as? String
  } catch (e: Exception) {
    null
  }?.takeIf { it.isNotEmpty() }

/** An approval prompt's sentences, queued one by one. */
private fun promptSentences(prompt: String): List<String> {
  val chunker = SentenceChunker()
  return chunker.take(prompt).map { it.second.trim() } + listOfNotNull(chunker.rest(prompt)?.second?.trim())
}

/** 16-bit little-endian PCM bytes as samples. */
private fun pcm16ToShorts(bytes: ByteArray): ShortArray =
  ShortArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
