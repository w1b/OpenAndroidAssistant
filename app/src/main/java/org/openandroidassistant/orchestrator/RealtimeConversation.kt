/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.orchestrator

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.openandroidassistant.audio.AudioBus
import org.openandroidassistant.audio.rms
import org.openandroidassistant.model.parseArguments
import org.openandroidassistant.realtime.LIVE_SAMPLE_RATE
import org.openandroidassistant.realtime.LiveConfig
import org.openandroidassistant.realtime.LiveEvent
import org.openandroidassistant.realtime.LiveSession
import org.openandroidassistant.tools.ApprovalLevel
import org.openandroidassistant.tools.AssistantTool
import org.openandroidassistant.tools.Handoff
import org.openandroidassistant.tools.ToolContext
import org.openandroidassistant.tools.ToolOutcome
import org.openandroidassistant.tools.ToolSchema

private const val TAG = "RealtimeConversation"
/** Mic audio is sent in 100 ms chunks. */
private const val SEND_CHUNK_BYTES = LIVE_SAMPLE_RATE * 2 / 10
private const val APPROVAL_SPEAK_TIMEOUT_MS = 8_000L

/** What a realtime conversation needs from the app around it. */
interface RealtimeHost {
  fun update(transform: (OverlayUiState) -> OverlayUiState)

  /** Shows the approval card, runs the local yes/no recognizer, returns the decision. */
  suspend fun approveLocally(request: ApprovalRequest): ApprovalDecision

  val isLocked: Boolean

  suspend fun requestUnlock(verb: String): Boolean

  /** Launch an app and end the conversation (open_app and friends). */
  fun handOff(handoff: Handoff)

  /** The session ended (closed for silence, dismissed, or failed). [transcript] continues it later. */
  fun onEnded(transcript: List<Pair<String, String>>, notice: String?)
}

/**
 * The realtime harness: GPT-Live talks with the user, its backend calls OAA's tools,
 * and the app runs each call through approval and returns the result. The app closes the session
 * a few seconds after the voice model stops speaking, when nobody spoke and nothing is pending.
 */
class RealtimeConversation(
  private val scope: CoroutineScope,
  private val config: LiveConfig,
  private val audioBus: AudioBus,
  private val host: RealtimeHost,
  private val tools: Map<String, AssistantTool>,
  private val schemas: Map<String, ToolSchema>,
  private val toolContext: ToolContext,
  private val approvalLevel: (AssistantTool) -> ApprovalLevel,
  private val allowedWhenLocked: (AssistantTool) -> Boolean,
  private val closeAfterMs: Long,
) {
  private val session = LiveSession(config)
  private val executor = ToolExecutor()
  private var micJob: Job? = null
  private var eventsJob: Job? = null
  private var idleJob: Job? = null
  private val player = Player()
  @Volatile private var muted = false
  @Volatile private var approvalPending = false
  @Volatile private var closing = false
  private val lastActivityAt = AtomicLong(SystemClock.elapsedRealtime())
  /** Delegations the backend is working on, and their unanswered calls. */
  private val activeDelegations = mutableSetOf<String>()
  private val pendingCalls = mutableMapOf<String, Int>()
  private val resultsAwaitingContinue = mutableSetOf<String>()
  private val stateLock = Mutex()
  private val transcript = mutableListOf<Pair<String, String>>()
  private var exchangeId = 0L
  private var speechPartId = 0L
  private var lastSpeaker: String? = null
  private val ids = AtomicLong(System.nanoTime())

  fun start() {
    host.update { it.copy(visible = true, realtime = RealtimeUi(), phase = AssistantPhase.IDLE, notice = null) }
    session.open()
    eventsJob = scope.launch { for (event in session.events) handle(event) }
  }

  private suspend fun handle(event: LiveEvent) {
    when (event) {
      LiveEvent.Started -> {
        Log.i(TAG, "session started")
        startMic()
        startIdleWatch()
      }
      is LiveEvent.OutputAudio -> {
        touch()
        player.write(event.pcm)
        host.update { s -> s.copy(realtime = s.realtime?.copy(assistantLevel = levelOf(event.pcm))) }
      }
      is LiveEvent.InputTranscript -> {
        touch()
        // Someone speaking cuts the voice model off; drop audio already queued.
        player.flush()
        caption("user", event.delta)
      }
      is LiveEvent.OutputTranscript -> caption("assistant", event.delta)
      is LiveEvent.DelegationStarted -> stateLock.withLock { activeDelegations += event.id }
      is LiveEvent.FunctionCall -> {
        val key = event.delegationId ?: "none"
        stateLock.withLock { pendingCalls[key] = (pendingCalls[key] ?: 0) + 1 }
        scope.launch { runCall(event) }
      }
      is LiveEvent.BackendDone -> {
        val key = event.delegationId ?: "none"
        stateLock.withLock {
          if ((pendingCalls[key] ?: 0) == 0 && key !in resultsAwaitingContinue) activeDelegations -= key
        }
        host.update { s -> s.copy(realtime = s.realtime?.copy(activity = null)) }
        touch()
      }
      is LiveEvent.Muted -> host.update { s -> s.copy(realtime = s.realtime?.copy(muted = event.muted || muted)) }
      is LiveEvent.Error -> {
        Log.w(TAG, "error ${event.code}: ${event.message}")
        if (event.fatal) finish(notice = "I can't reach OpenAI right now.")
      }
      is LiveEvent.Closed -> finish(notice = null)
    }
  }

  // ---------------------------------------------------------------- audio

  private fun startMic() {
    micJob =
      scope.launch(Dispatchers.IO) {
        val buffer = ByteArrayOutputStream()
        audioBus.listen { frame ->
          val level = frame.rms()
          host.update { s -> s.copy(realtime = s.realtime?.copy(userLevel = if (muted || approvalPending) 0f else level)) }
          if (!muted && !approvalPending) {
            for (sample in frame) {
              buffer.write(sample.toInt() and 0xFF)
              buffer.write((sample.toInt() shr 8) and 0xFF)
            }
            if (buffer.size() >= SEND_CHUNK_BYTES) {
              session.appendAudio(buffer.toByteArray())
              buffer.reset()
            }
          }
          closing
        }
      }
  }

  /** The mute toggle. */
  fun toggleMute() {
    muted = !muted
    session.setMuted(muted)
    host.update { s -> s.copy(realtime = s.realtime?.copy(muted = muted)) }
  }

  fun sendTyped(text: String) {
    touch()
    newExchange(UserEntry.Text(text, typed = true))
    transcript += "user" to text
    session.sendTyped(text)
  }

  private fun levelOf(pcm: ByteArray): Float {
    val shorts = ShortArray(pcm.size / 2) { i -> ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort() }
    return shorts.rms()
  }

  // ---------------------------------------------------------------- captions

  private fun caption(speaker: String, delta: String) {
    if (speaker == "user") {
      if (lastSpeaker != "user") {
        newExchange(UserEntry.Text("", typed = false))
        transcript += "user" to ""
      }
      transcript[transcript.lastIndex] = "user" to transcript.last().second + delta
      val text = transcript.last().second
      host.update { s ->
        s.copy(
          realtime = s.realtime?.copy(userCaption = text),
          exchanges = s.exchanges.map { if (it.id == exchangeId) it.copy(user = UserEntry.Text(text.trim(), typed = false)) else it },
        )
      }
    } else {
      if (exchangeId == 0L) newExchange(null)
      if (lastSpeaker != "assistant") {
        speechPartId = ids.incrementAndGet()
        transcript += "assistant" to ""
        val part = ExchangePart.Speech(speechPartId, "")
        host.update { s -> s.copy(exchanges = s.exchanges.map { if (it.id == exchangeId) it.copy(parts = it.parts + part) else it }) }
      }
      transcript[transcript.lastIndex] = "assistant" to transcript.last().second + delta
      val text = transcript.last().second
      host.update { s ->
        s.copy(
          exchanges =
            s.exchanges.map { ex ->
              if (ex.id != exchangeId) ex
              else ex.copy(parts = ex.parts.map { p -> if (p is ExchangePart.Speech && p.id == speechPartId) p.copy(text = text.trim()) else p })
            }
        )
      }
    }
    lastSpeaker = speaker
  }

  private fun newExchange(user: UserEntry?) {
    exchangeId = ids.incrementAndGet()
    lastSpeaker = null
    val exchange = Exchange(exchangeId, user)
    host.update { it.copy(exchanges = it.exchanges + exchange) }
  }

  private fun addCards(cards: List<RenderCard>) {
    if (cards.isEmpty()) return
    if (exchangeId == 0L) newExchange(null)
    host.update { s ->
      s.copy(exchanges = s.exchanges.map { ex -> if (ex.id == exchangeId) ex.copy(parts = ex.parts + cards.map(ExchangePart::Card).filter { it !in ex.parts }) else ex })
    }
  }

  // ---------------------------------------------------------------- tools

  private suspend fun runCall(call: LiveEvent.FunctionCall) {
    touch()
    val output = execute(call)
    session.sendFunctionOutput(call.callId, output)
    val key = call.delegationId ?: "none"
    val continueNow =
      stateLock.withLock {
        val left = ((pendingCalls[key] ?: 1) - 1).coerceAtLeast(0)
        pendingCalls[key] = left
        resultsAwaitingContinue += key
        if (left == 0) {
          resultsAwaitingContinue -= key
          true
        } else false
      }
    // All of this response's calls are answered: let the backend continue.
    if (continueNow) session.continueBackend()
    touch()
  }

  private suspend fun execute(call: LiveEvent.FunctionCall): String {
    val args = parseArguments(call.arguments)
    if (call.name == TurnLoop.SHOW_TOOL) {
      val schema = schemas.getValue(TurnLoop.SHOW_TOOL)
      return when (val v = ToolCallValidator.validate(schema, args, toolContext.handleTable)) {
        is ToolCallValidator.Result.Invalid -> errorJson(v.instruction)
        is ToolCallValidator.Result.Valid -> {
          addCards(v.call.handleLists["items"].orEmpty().mapNotNull { org.openandroidassistant.overlay.RendererRegistry.render(it) })
          v.call.stringArg("text")?.let { md ->
            host.update { s -> s.copy(exchanges = s.exchanges.map { ex -> if (ex.id == exchangeId) ex.copy(parts = ex.parts + ExchangePart.Markdown(md)) else ex }) }
          }
          okJson("Shown.")
        }
      }
    }
    val tool = tools[call.name] ?: return errorJson("There is no tool named '${call.name}'.")
    val validated =
      when (val v = ToolCallValidator.validate(schemas.getValue(call.name), args, toolContext.handleTable)) {
        is ToolCallValidator.Result.Valid -> v.call
        is ToolCallValidator.Result.Invalid -> return errorJson(v.instruction)
      }
    if (host.isLocked && !allowedWhenLocked(tool) && !host.requestUnlock(tool.metadata.unlockVerb ?: "do that")) {
      return declinedJson("The phone is locked and the user didn't unlock it, so nothing was done.")
    }
    val level = approvalLevel(tool)
    if (level != ApprovalLevel.NONE) {
      val decision = approve(ApprovalRequest(tool, validated, tool.approvalContent(validated, toolContext), level))
      if (decision is ApprovalDecision.Declined) {
        val said = decision.replyTranscript?.takeIf { it.isNotBlank() }
        return declinedJson("The user declined, so nothing was done." + (said?.let { " They said: \"$it\"" } ?: ""))
      }
    }
    host.update { s -> s.copy(realtime = s.realtime?.copy(activity = tool.metadata.progressPhrase?.removeSuffix(".")?.plus("…"))) }
    val result = executor.run(tool, validated, toolContext)
    addCards(result.autoShown.mapNotNull { org.openandroidassistant.overlay.RendererRegistry.render(it) })
    (result.outcome as? ToolOutcome.Success)?.handoff?.let { handoff ->
      session.appendCommentary(handoff.phrase)
      scope.launch {
        delay(1_500)
        host.handOff(handoff)
        close()
      }
    }
    return result.envelopeJson
  }

  /**
   * Realtime approval: the card shows, the user's mic is muted to the voice model,
   * the voice model is asked to say the approval question, and the local recognizer decides.
   */
  private suspend fun approve(request: ApprovalRequest): ApprovalDecision {
    approvalPending = true
    session.setMuted(true)
    try {
      session.appendInstructions(
        "An approval is pending. The user's microphone is muted to you and the phone is listening for their answer. " +
          "Ask them this now, then stay quiet until the result arrives, and don't say it's done: ${request.content.spokenPrompt}"
      )
      // Let the voice model ask before the local recognizer listens, so it doesn't hear the question.
      withTimeoutOrNull(APPROVAL_SPEAK_TIMEOUT_MS) {
        delay(800)
        while (SystemClock.elapsedRealtime() - player.lastWriteAt < 700) delay(100)
      }
      return host.approveLocally(request)
    } finally {
      approvalPending = false
      if (!muted) session.setMuted(false)
      touch()
    }
  }

  // ---------------------------------------------------------------- ending

  private fun touch() = lastActivityAt.set(SystemClock.elapsedRealtime())

  /** Closes the session a few seconds after things go quiet with nothing pending. */
  private fun startIdleWatch() {
    idleJob =
      scope.launch {
        while (!closing) {
          delay(500)
          val quietFor = SystemClock.elapsedRealtime() - maxOf(lastActivityAt.get(), player.lastWriteAt + player.bufferedMs())
          val busy = stateLock.withLock { activeDelegations.isNotEmpty() || pendingCalls.values.any { it > 0 } } || approvalPending
          if (!busy && quietFor > closeAfterMs) {
            Log.i(TAG, "closing after ${quietFor}ms of quiet")
            close()
          }
        }
      }
  }

  fun close() {
    if (closing) return
    closing = true
    session.close()
    scope.launch {
      delay(5_000)
      session.abort()
      finish(null)
    }
  }

  private var finished = false

  private fun finish(notice: String?) {
    if (finished) return
    finished = true
    closing = true
    micJob?.cancel()
    idleJob?.cancel()
    player.release()
    session.events.close()
    host.update { it.copy(realtime = null) }
    host.onEnded(transcript.filter { it.second.isNotBlank() }, notice)
  }

  /** Streams the voice model's 16 kHz PCM to the speaker. */
  private inner class Player {
    private val queue = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile var lastWriteAt = 0L
    private val track =
      AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(LIVE_SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(LIVE_SAMPLE_RATE * 2)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    private var queuedBytes = AtomicLong()
    private val job =
      scope.launch(Dispatchers.IO) {
        track.play()
        for (chunk in queue) {
          track.write(chunk, 0, chunk.size)
          queuedBytes.addAndGet(-chunk.size.toLong())
        }
      }

    fun write(pcm: ByteArray) {
      lastWriteAt = SystemClock.elapsedRealtime()
      queuedBytes.addAndGet(pcm.size.toLong())
      queue.trySend(pcm)
    }

    fun bufferedMs(): Long = queuedBytes.get() * 1000 / (LIVE_SAMPLE_RATE * 2)

    fun flush() {
      while (queue.tryReceive().isSuccess) {}
      queuedBytes.set(0)
      runCatching {
        track.pause()
        track.flush()
        track.play()
      }
    }

    fun release() {
      queue.close()
      job.cancel()
      runCatching {
        track.stop()
        track.release()
      }
    }
  }
}

private fun okJson(note: String) = com.google.gson.JsonObject().apply {
  addProperty("status", "ok")
  addProperty("note", note)
}.toString()

private fun errorJson(instruction: String) = com.google.gson.JsonObject().apply {
  addProperty("status", "error")
  addProperty("instruction", instruction)
}.toString()

private fun declinedJson(note: String) = com.google.gson.JsonObject().apply {
  addProperty("status", "declined")
  addProperty("note", note)
}.toString()
