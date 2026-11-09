/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.model

import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.OkHttpClient
import org.openandroidassistant.runtime.toWavBytes

private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"

/** Debug builds: receives request bodies for inspection. */
@Volatile var debugDump: ((name: String, content: String) -> Unit)? = null

/** What OpenRouter reports about a model (from its models and endpoints APIs). */
data class OpenRouterModelInfo(
  val id: String,
  val name: String,
  val acceptsAudio: Boolean,
  val supportsTools: Boolean,
  val supportsReasoning: Boolean,
  /** The endpoint lists `openrouter:web_search` in `native_tools`. */
  val hasNativeWebSearch: Boolean,
)

/**
 * A turn-based model served through OpenRouter's chat completions API. Audio goes up as
 * `input_audio` when the model accepts it; otherwise the app sends a local transcript.
 */
class OpenRouterChatModel(
  private val apiKey: String,
  val info: OpenRouterModelInfo,
  /** `minimal` … `xhigh`, or null to leave reasoning at the provider default. */
  private val reasoningEffort: String?,
  private val client: OkHttpClient = sharedHttpClient,
) : ChatModel {
  override val displayName: String = info.name
  override val kind: ModelKind = if (info.acceptsAudio) ModelKind.AUDIO_IN else ModelKind.TEXT_ONLY

  override suspend fun openSession(spec: SessionSpec): ChatSession =
    OpenRouterSession(this, spec)

  internal fun requestBody(spec: SessionSpec, messages: List<JsonObject>): JsonObject =
    buildJsonObject {
      put("model", info.id)
      put("stream", true)
      putJsonArray("messages") {
        add(buildJsonObject {
          put("role", "system")
          put("content", spec.systemPrompt)
        })
        messages.forEach { add(it) }
      }
      if (spec.tools.isNotEmpty() || spec.nativeWebSearch) {
        putJsonArray("tools") {
          for (tool in spec.tools) {
            add(buildJsonObject {
              put("type", "function")
              putJsonObject("function") {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parametersJsonSchema())
              }
            })
          }
          if (spec.nativeWebSearch && info.hasNativeWebSearch) {
            // Native only: never let OpenRouter substitute its own search engine.
            add(buildJsonObject {
              put("type", "openrouter:web_search")
              putJsonObject("parameters") { put("engine", "native") }
            })
          }
        }
      }
      if (reasoningEffort != null && info.supportsReasoning) {
        putJsonObject("reasoning") {
          put("effort", reasoningEffort)
          // Reasoning is never shown, but it must be returned to the model.
          put("exclude", false)
        }
      }
    }

  internal suspend fun stream(
    body: JsonObject,
    callHolder: (Call) -> Unit,
    onEvent: (String) -> Boolean,
  ): StreamOutcome =
    postServerSentEvents(
      client = client,
      url = OPENROUTER_URL,
      headers =
        mapOf(
          "Authorization" to "Bearer $apiKey",
          "HTTP-Referer" to "https://github.com/openandroidassistant",
          "X-Title" to "OpenAndroidAssistant",
        ),
      body = body,
      callHolder = callHolder,
      onEvent = onEvent,
    )
}

private class OpenRouterSession(
  private val model: OpenRouterChatModel,
  private val spec: SessionSpec,
) : ChatSession {
  private val messages = mutableListOf<JsonObject>()
  private val activeCall = AtomicReference<Call?>()

  override suspend fun run(input: ModelInput, onText: (String) -> Unit): RunResult {
    val pending = buildList {
      for (result in input.toolResults) {
        add(buildJsonObject {
          put("role", "tool")
          put("tool_call_id", result.callId)
          put("content", result.content)
        })
      }
      input.user?.let { add(userMessage(it)) }
    }
    val body = model.requestBody(spec, messages + pending)
    debugDump?.invoke("openrouter-request", body.toString())
    val accumulator = ChatStreamAccumulator()
    var streamError: ModelError? = null
    val outcome =
      model.stream(body, callHolder = { activeCall.set(it) }) { data ->
        val chunk = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
        when {
          chunk == null -> true
          chunk["error"] != null -> {
            streamError =
              ModelError.Failed(
                (chunk["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                  ?: chunk["error"].toString()
              )
            false
          }
          else -> {
            if (accumulator.accept(chunk)) onText(accumulator.text.toString())
            true
          }
        }
      }
    activeCall.set(null)
    streamError?.let { return RunResult.Error(it) }
    return when (outcome) {
      is StreamOutcome.Cancelled -> RunResult.Cancelled(accumulator.text.toString())
      is StreamOutcome.Failed -> RunResult.Error(outcome.error)
      is StreamOutcome.Done -> {
        val message = accumulator.message()
        // History only grows once a run completes, so a failed run can simply be retried.
        messages += pending
        messages += accumulator.assistantMessage()
        RunResult.Complete(message)
      }
    }
  }

  override fun cancel() {
    activeCall.get()?.cancel()
  }

  private fun userMessage(user: UserContent): JsonObject = buildJsonObject {
    put("role", "user")
    putJsonArray("content") {
      user.audio?.let { audio ->
        add(buildJsonObject {
          put("type", "input_audio")
          putJsonObject("input_audio") {
            put("data", Base64.getEncoder().encodeToString(audio.toWavBytes()))
            put("format", "wav")
          }
        })
      }
      val text = listOfNotNull(user.text, user.context).filter { it.isNotBlank() }
      if (text.isNotEmpty()) {
        add(buildJsonObject {
          put("type", "text")
          put("text", text.joinToString("\n\n"))
        })
      }
    }
  }
}

/** Rebuilds one assistant message from chat-completions stream chunks. */
internal class ChatStreamAccumulator {
  val text = StringBuilder()
  private val calls = sortedMapOf<Int, PartialCall>()
  private val reasoning = sortedMapOf<Int, MutableMap<String, JsonElement>>()
  private var reasoningIndex = 0

  private class PartialCall(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

  /** Returns true when visible text changed. */
  fun accept(chunk: JsonObject): Boolean {
    val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return false
    val delta = (choice["delta"] ?: choice["message"]) as? JsonObject ?: return false
    var textChanged = false
    (delta["content"] as? JsonPrimitive)?.contentOrNull?.let {
      if (it.isNotEmpty()) {
        text.append(it)
        textChanged = true
      }
    }
    (delta["tool_calls"] as? JsonArray)?.forEach { raw ->
      val call = raw as? JsonObject ?: return@forEach
      val index = call["index"]?.jsonPrimitive?.intOrNull ?: calls.size
      val partial = calls.getOrPut(index) { PartialCall() }
      call["id"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) partial.id = it }
      (call["function"] as? JsonObject)?.let { fn ->
        // Providers send the name once, on the call's first chunk.
        fn["name"]?.jsonPrimitive?.contentOrNull?.let { if (partial.name.isEmpty()) partial.name = it }
        fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { partial.args.append(it) }
      }
    }
    (delta["reasoning_details"] as? JsonArray)?.forEach { raw ->
      val detail = raw as? JsonObject ?: return@forEach
      val index = detail["index"]?.jsonPrimitive?.intOrNull ?: reasoningIndex
      reasoningIndex = maxOf(reasoningIndex, index)
      val merged = reasoning.getOrPut(index) { mutableMapOf() }
      for ((key, value) in detail) {
        val existing = merged[key]
        merged[key] =
          if (
            key in CONCATENATED_FIELDS &&
              existing is JsonPrimitive &&
              existing.isString &&
              value is JsonPrimitive &&
              value.isString
          ) {
            JsonPrimitive(existing.content + value.content)
          } else {
            value
          }
      }
    }
    return textChanged
  }

  fun message(): ModelMessage =
    ModelMessage(
      text = text.toString(),
      toolCalls =
        calls.values
          .filter { it.name.isNotEmpty() }
          .mapIndexed { i, call ->
            ModelToolCall(
              id = call.id.ifEmpty { "call_$i" },
              name = call.name,
              args = parseArguments(call.args.toString()),
            )
          },
    )

  fun assistantMessage(): JsonObject = buildJsonObject {
    put("role", "assistant")
    put("content", text.toString())
    val finished = calls.values.filter { it.name.isNotEmpty() }
    if (finished.isNotEmpty()) {
      putJsonArray("tool_calls") {
        finished.forEachIndexed { i, call ->
          add(buildJsonObject {
            put("id", call.id.ifEmpty { "call_$i" })
            put("type", "function")
            putJsonObject("function") {
              put("name", call.name)
              put("arguments", call.args.toString().ifBlank { "{}" })
            }
          })
        }
      }
    }
    if (reasoning.isNotEmpty()) {
      put("reasoning_details", buildJsonArray { reasoning.values.forEach { add(JsonObject(it)) } })
    }
  }

  private companion object {
    val CONCATENATED_FIELDS = setOf("text", "summary", "data", "signature")
  }
}

/** Reads [OpenRouterModelInfo] from OpenRouter's public models API (no key needed). */
object OpenRouterCatalog {
  private const val MODELS_URL = "https://openrouter.ai/api/v1/models"

  fun parseModels(body: String): List<OpenRouterModelInfo> =
    json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray.mapNotNull { raw ->
      val m = raw as? JsonObject ?: return@mapNotNull null
      val id = m["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
      val architecture = m["architecture"] as? JsonObject
      val inputs = (architecture?.get("input_modalities") as? JsonArray)?.mapNotNull {
        it.jsonPrimitive.contentOrNull
      }.orEmpty()
      val outputs = (architecture?.get("output_modalities") as? JsonArray)?.mapNotNull {
        it.jsonPrimitive.contentOrNull
      }.orEmpty()
      val params = (m["supported_parameters"] as? JsonArray)?.mapNotNull {
        it.jsonPrimitive.contentOrNull
      }.orEmpty()
      if ("text" !in outputs || "tools" !in params) return@mapNotNull null
      OpenRouterModelInfo(
        id = id,
        name = m["name"]?.jsonPrimitive?.contentOrNull ?: id,
        acceptsAudio = "audio" in inputs,
        supportsTools = true,
        supportsReasoning = "reasoning" in params || "reasoning_effort" in params,
        hasNativeWebSearch = false, // only the endpoints API reports native tools
      )
    }

  /** True when any endpoint of the model lists native web search. */
  fun parseNativeWebSearch(endpointsBody: String): Boolean =
    runCatching {
        val endpoints =
          json.parseToJsonElement(endpointsBody).jsonObject["data"]!!.jsonObject["endpoints"]!!.jsonArray
        endpoints.any { e ->
          ((e as? JsonObject)?.get("native_tools") as? JsonObject)?.containsKey("openrouter:web_search") ==
            true
        }
      }
      .getOrDefault(false)

  fun modelsUrl(): String = MODELS_URL

  fun endpointsUrl(id: String): String = "$MODELS_URL/$id/endpoints"
}
