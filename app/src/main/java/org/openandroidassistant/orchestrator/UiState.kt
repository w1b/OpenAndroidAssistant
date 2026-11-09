/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.orchestrator

import org.openandroidassistant.model.ModelKind
import org.openandroidassistant.tools.ApprovalCard
import org.openandroidassistant.tools.ApprovalLevel

/** Turn-based states shown in the overlay. */
enum class AssistantPhase {
  IDLE,
  LISTENING,
  THINKING,
  SPEAKING,
  APPROVAL,
  /** A local model is still loading. */
  WAKING_UP,
}

/** How a conversation was started or re-entered. */
enum class TriggerSource {
  WAKE_WORD,
  QS_TILE,
  IN_APP,
  VOICE_COMMAND_INTENT,
}

/** One app-rendered item. Payloads of result ids; the model never shapes these directly. */
sealed interface RenderCard {
  data class Weather(
    val place: String,
    val summary: String,
    val temperature: String,
    val high: String,
    val low: String,
    val precipitationChance: String?,
    /** The rest of the day, hour by hour, for the scrollable strip. */
    val hours: List<HourForecast> = emptyList(),
  ) : RenderCard

  data class HourForecast(val label: String, val temperature: String, val summary: String, val precipitationChance: String?)

  data class Contact(
    val name: String,
    val phone: String?,
    val photoUri: String?,
    val phoneLabel: String? = null,
    val address: String? = null,
  ) : RenderCard

  data class Message(
    val from: String,
    val body: String,
    val timeLabel: String,
    /** A message the user sent (or the assistant sent for them). */
    val outgoing: Boolean = false,
  ) : RenderCard

  data class Event(
    val title: String,
    val timeLabel: String,
    val location: String?,
    val calendarColor: Int? = null,
  ) : RenderCard

  data class Alarm(val timeLabel: String, val label: String?, val enabled: Boolean, val daysLabel: String? = null) : RenderCard

  /** A live countdown: [endsAtMillis] is wall-clock time; [pausedRemainingMs] when paused. */
  data class Timer(
    val endsAtMillis: Long,
    val totalMs: Long,
    val label: String?,
    val pausedRemainingMs: Long? = null,
  ) : RenderCard

  data class Reminder(val text: String, val timeLabel: String?) : RenderCard

  data class Note(val title: String, val body: String) : RenderCard

  data class Place(
    val name: String,
    val address: String?,
    val distanceLabel: String?,
    val hoursLabel: String?,
    val phone: String?,
    val category: String? = null,
  ) : RenderCard

  data class Notification(val app: String, val title: String, val text: String, val timeLabel: String) : RenderCard

  data class NowPlaying(val title: String, val artist: String?, val app: String?, val playing: Boolean) : RenderCard

  data class WebResult(val title: String, val url: String, val snippet: String?) : RenderCard

  /** A completed device or call action ("Calling Harold Finch"). */
  data class ActionDone(val summary: String) : RenderCard
}

/** One thing on screen inside an exchange, in the order it happened. */
sealed interface ExchangePart {
  /** Text the assistant spoke. [revealedChars] lights up in sync with speech; null when not playing. */
  data class Speech(val id: Long, val text: String, val revealedChars: Int? = null) : ExchangePart

  data class Card(val card: RenderCard) : ExchangePart

  data class Markdown(val text: String) : ExchangePart
}

/** What the user said in an exchange. */
sealed interface UserEntry {
  /** Heard directly by the model; no transcript exists (native audio in). */
  data object Voice : UserEntry

  data class Text(val text: String, val typed: Boolean) : UserEntry
}

/** One user turn and everything the assistant said and showed in response. */
data class Exchange(val id: Long, val user: UserEntry?, val parts: List<ExchangePart> = emptyList())

/** The approval card as shown. */
data class ApprovalUi(
  val card: ApprovalCard,
  val level: ApprovalLevel,
  /** The local recognizer is listening for yes/no. */
  val listening: Boolean,
  /** Realtime: the voice model can't hear the user right now. */
  val realtime: Boolean = false,
)

/** Realtime-only overlay state. */
data class RealtimeUi(
  val userLevel: Float = 0f,
  val assistantLevel: Float = 0f,
  val muted: Boolean = false,
  /** "Checking your calendar…" while a tool runs. */
  val activity: String? = null,
  /** Live caption of what the user is saying. */
  val userCaption: String = "",
)

/** Everything the overlay renders. */
data class OverlayUiState(
  val visible: Boolean = false,
  val phase: AssistantPhase = AssistantPhase.IDLE,
  val modelKind: ModelKind = ModelKind.AUDIO_IN,
  /** Smoothed mic level 0..1 while listening. */
  val micLevel: Float = 0f,
  /** Recent mic levels for the native-audio-in waveform that builds up while you speak. */
  val waveform: List<Float> = emptyList(),
  /** Text-only: what the app thinks you're saying, streaming. */
  val liveTranscript: String = "",
  /** Thinking: the tool's progress text while tools run ("Checking your messages…"). */
  val workingLabel: String? = null,
  /** The conversation so far; the last one is front and center. */
  val exchanges: List<Exchange> = emptyList(),
  val approval: ApprovalUi? = null,
  val unlockPrompt: String? = null,
  /** The keyboard is open for a typed turn. */
  val typing: Boolean = false,
  /** Non-null while a realtime session is open. */
  val realtime: RealtimeUi? = null,
  /** A one-line error shown with the reply, e.g. "Can't reach OpenRouter". */
  val notice: String? = null,
)
