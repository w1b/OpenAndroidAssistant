/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.realtime

import android.util.Base64
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.openandroidassistant.model.parametersJsonSchema
import org.openandroidassistant.model.sharedHttpClient
import org.openandroidassistant.tools.ToolSchema

private const val TAG = "LiveSession"
private const val LIVE_URL = "wss://api.openai.com/v1/live/sessions"
const val LIVE_MODEL = "gpt-live-1"
/** Mic frames are already 16 kHz mono PCM16, one of GPT-Live's formats, so no resampling. */
const val LIVE_SAMPLE_RATE = 16_000

/** Everything fixed when a GPT-Live session starts. */
data class LiveConfig(
  val apiKey: String,
  val instructions: String,
  /** Earlier conversation text, to continue it in a new session. */
  val history: List<Pair<String, String>>,
  val backendModel: String,
  val backendReasoning: String,
  val backendInstructions: String,
  val tools: List<ToolSchema>,
  val webSearch: Boolean,
  val voice: String = "marin",
)

/** What the session tells the app, as plain Kotlin values. */
sealed interface LiveEvent {
  data object Started : LiveEvent

  class OutputAudio(val pcm: ByteArray) : LiveEvent

  data class InputTranscript(val delta: String) : LiveEvent

  data class OutputTranscript(val delta: String) : LiveEvent

  data class DelegationStarted(val id: String) : LiveEvent

  /** The backend finished a response (it may have asked for tools). */
  data class BackendDone(val delegationId: String?) : LiveEvent

  data class FunctionCall(val delegationId: String?, val callId: String, val name: String, val arguments: String) : LiveEvent

  data class Muted(val muted: Boolean) : LiveEvent

  data class Error(val code: String?, val message: String, val fatal: Boolean) : LiveEvent

  data class Closed(val reason: String?) : LiveEvent
}

/**
 * One GPT-Live session over WebSocket with Responses delegation. The voice model talks; the
 * backend calls OAA's tools, which arrive here as function calls for the app to approve and run.
 */
class LiveSession(private val config: LiveConfig, private val client: OkHttpClient = sharedHttpClient) {
  val events = Channel<LiveEvent>(Channel.UNLIMITED)
  private var socket: WebSocket? = null
  private val ids = AtomicLong()

  fun open() {
    val request = Request.Builder().url(liveUrl()).header("Authorization", "Bearer ${config.apiKey}").build()
    socket =
      client.newWebSocket(
        request,
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(startEvent().toString())
          }

          override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { handle(org.openandroidassistant.model.json.parseToJsonElement(text).jsonObject) }
              .onFailure { Log.w(TAG, "Bad event", it) }
          }

          override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "Connection failed", t)
            events.trySend(LiveEvent.Error("connection", t.message ?: "Connection failed", fatal = true))
            events.trySend(LiveEvent.Closed("connection_lost"))
          }

          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            events.trySend(LiveEvent.Closed(reason.ifBlank { null }))
          }
        },
      )
  }

  private fun startEvent(): JsonObject = buildJsonObject {
    put("type", "session.start")
    put("event_id", nextId())
    putJsonObject("session") {
      put("model", LIVE_MODEL)
      put("instructions", config.instructions)
      if (config.history.isNotEmpty()) {
        putJsonArray("input") {
          for ((role, text) in config.history.takeLast(64)) {
            add(buildJsonObject {
              put("type", "message")
              put("role", role)
              putJsonArray("content") {
                add(buildJsonObject {
                  put("type", if (role == "assistant") "output_text" else "input_text")
                  put("text", text)
                })
              }
            })
          }
        }
      }
      putJsonObject("audio") {
        putJsonObject("format") {
          put("type", "audio/pcm")
          put("rate", LIVE_SAMPLE_RATE)
        }
        putJsonObject("output") { put("voice", config.voice) }
      }
      putJsonObject("delegation") {
        put("type", "responses")
        putJsonObject("responses") {
          put("model", config.backendModel)
          put("instructions", config.backendInstructions)
          putJsonObject("reasoning") { put("effort", config.backendReasoning) }
          put("tool_choice", "auto")
          put("parallel_tool_calls", true)
          putJsonArray("tools") {
            for (tool in config.tools) {
              add(buildJsonObject {
                put("type", "function")
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parametersJsonSchema())
              })
            }
            if (config.webSearch) add(buildJsonObject { put("type", "web_search") })
          }
        }
      }
    }
  }

  private fun handle(event: JsonObject) {
    when (event.str("type")) {
      "session.started" -> events.trySend(LiveEvent.Started)
      "session.output_audio.delta" ->
        event.str("delta")?.let { events.trySend(LiveEvent.OutputAudio(Base64.decode(it, Base64.DEFAULT))) }
      "session.input_transcript.delta" -> event.str("delta")?.let { events.trySend(LiveEvent.InputTranscript(it)) }
      "session.output_transcript.delta" -> event.str("delta")?.let { events.trySend(LiveEvent.OutputTranscript(it)) }
      "session.delegation.created" -> {
        val id = (event["delegation"] as? JsonObject)?.str("id") ?: event.str("delegation_id") ?: return
        events.trySend(LiveEvent.DelegationStarted(id))
      }
      "session.input_audio.muted" -> events.trySend(LiveEvent.Muted(true))
      "session.input_audio.unmuted" -> events.trySend(LiveEvent.Muted(false))
      "response.event" -> {
        val delegationId = event.str("delegation_id")
        val inner = event["event"] as? JsonObject ?: return
        when (inner.str("type")) {
          "response.output_item.done" -> {
            val item = inner["item"] as? JsonObject ?: return
            if (item.str("type") == "function_call") {
              events.trySend(
                LiveEvent.FunctionCall(delegationId, item.str("call_id").orEmpty(), item.str("name").orEmpty(), item.str("arguments").orEmpty())
              )
            }
          }
          "response.completed",
          "response.incomplete",
          "response.failed" -> events.trySend(LiveEvent.BackendDone(delegationId))
        }
      }
      "error" -> {
        val error = event["error"] as? JsonObject
        val code = error?.str("code")
        events.trySend(LiveEvent.Error(code, error?.str("message") ?: "Error", fatal = false))
      }
      "session.closed" -> {
        events.trySend(LiveEvent.Closed(event.str("reason")))
        socket?.close(1000, null)
      }
    }
  }

  fun appendAudio(pcm: ByteArray) = send(buildJsonObject {
    put("type", "session.input_audio.append")
    put("audio", Base64.encodeToString(pcm, Base64.NO_WRAP))
  })

  fun setMuted(muted: Boolean) = send(buildJsonObject {
    put("type", if (muted) "session.input_audio.mute" else "session.input_audio.unmute")
    put("event_id", nextId())
  })

  /** Trusted app instructions for the voice model, such as an approval prompt. */
  fun appendInstructions(text: String) = append("session.instructions.append", text)

  /** Something for the voice model to say aloud, which it may paraphrase. */
  fun appendCommentary(text: String) = append("session.commentary.append", text)

  private fun append(type: String, text: String) = send(buildJsonObject {
    put("type", type)
    put("event_id", nextId())
    put("delegation_id", JsonNull)
    put("content", text.take(1800))
  })

  /** A tool result for the backend. Send all pending results, then [continueBackend]. */
  fun sendFunctionOutput(callId: String, output: String) = send(buildJsonObject {
    put("type", "response.item.create")
    put("event_id", nextId())
    putJsonObject("item") {
      put("type", "function_call_output")
      put("call_id", callId)
      put("output", output)
    }
  })

  fun continueBackend() = send(buildJsonObject {
    put("type", "response.create")
    put("event_id", nextId())
  })

  /** Typed input goes to the backend as the user's words. */
  fun sendTyped(text: String) {
    send(buildJsonObject {
      put("type", "response.item.create")
      put("event_id", nextId())
      putJsonObject("item") {
        put("type", "message")
        put("role", "user")
        putJsonArray("content") {
          add(buildJsonObject {
            put("type", "input_text")
            put("text", text)
          })
        }
      }
    })
    continueBackend()
  }

  fun close() {
    send(buildJsonObject {
      put("type", "session.close")
      put("event_id", nextId())
    })
  }

  /** Drops the connection without waiting for session.closed. */
  fun abort() {
    socket?.cancel()
  }

  private fun send(event: JsonObject) {
    socket?.send(event.toString())
  }

  private fun nextId() = "oaa_${ids.incrementAndGet()}"

  /** Debug builds can point at a local mock server: setprop debug.oaa.live_url ws://10.0.2.2:8765 */
  private fun liveUrl(): String =
    if (org.openandroidassistant.BuildConfig.DEBUG) org.openandroidassistant.orchestrator.systemProperty("debug.oaa.live_url") ?: LIVE_URL else LIVE_URL
}

private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
