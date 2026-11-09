/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.model

import org.openandroidassistant.runtime.AudioSegment
import org.openandroidassistant.tools.ToolSchema

/** How the active model hears the user and speaks back. */
enum class ModelKind {
  /** Local speech-to-text in, local text-to-speech out. */
  TEXT_ONLY,
  /** The model hears the user's audio; its text goes to local text-to-speech. */
  AUDIO_IN,
  /** Full-duplex native audio (GPT-Live); runs the realtime harness, not the turn loop. */
  REALTIME,
}

/** A tool call the model asked for. [args] is the decoded JSON object. */
data class ModelToolCall(val id: String, val name: String, val args: Map<String, Any?>)

/** One model run's output: text, tool calls, or both. */
data class ModelMessage(val text: String, val toolCalls: List<ModelToolCall>)

/** The app's answer to one earlier tool call. */
data class ToolResult(val callId: String, val name: String, val content: String)

/** What the user said or typed in one turn, plus context the app already knows. */
data class UserContent(
  /** The user's speech, for native-audio-in models. */
  val audio: AudioSegment? = null,
  /** A transcript or typed text. */
  val text: String? = null,
  /** App-supplied context sent alongside, such as the current time. */
  val context: String? = null,
)

/**
 * Everything appended before one model run: results for every tool call in the previous message
 * (if any), then optionally a user message.
 */
data class ModelInput(
  val toolResults: List<ToolResult> = emptyList(),
  val user: UserContent? = null,
) {
  init {
    require(toolResults.isNotEmpty() || user != null) { "A model run needs new input." }
  }
}

/** Fixed for a conversation: the system prompt and the tools the model may call. */
data class SessionSpec(
  val systemPrompt: String,
  val tools: List<ToolSchema>,
  /** Ask the provider to enable its own web search tool. */
  val nativeWebSearch: Boolean = false,
)

sealed interface ModelError {
  val message: String

  /** The provider couldn't be reached at all (no network, DNS, timeout before any reply). */
  data class Unreachable(override val message: String) : ModelError

  /** The provider answered with an error, or the local model failed. */
  data class Failed(override val message: String) : ModelError
}

sealed interface RunResult {
  data class Complete(val message: ModelMessage) : RunResult

  /** [cancel] was called; [partialText] is what streamed out before. */
  data class Cancelled(val partialText: String) : RunResult

  data class Error(val error: ModelError) : RunResult
}

/** One live conversation with a model. History is append-only. */
interface ChatSession : AutoCloseable {
  /**
   * Appends [input] and runs the model once. [onText] receives the cumulative visible text as it
   * streams.
   */
  suspend fun run(input: ModelInput, onText: (String) -> Unit = {}): RunResult

  /** Aborts an in-flight [run] from any thread. */
  fun cancel()

  override fun close() {}
}

/** A turn-based model (text-only or native audio in). */
interface ChatModel {
  /** Display name, e.g. "Gemini 3.8 Flash". */
  val displayName: String

  val kind: ModelKind

  /** True for an on-device model that has to be loaded first ("Waking up"). */
  val isLocal: Boolean
    get() = false

  /** Loads a local model or warms a connection. Idempotent. */
  suspend fun prepare() {}

  suspend fun openSession(spec: SessionSpec): ChatSession
}
