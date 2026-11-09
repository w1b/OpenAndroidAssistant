/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.model

import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.OkHttpClient

private const val RESPONSES_URL = "https://api.openai.com/v1/responses"

/**
 * A turn-based OpenAI model over the Responses API. The Responses API takes text, images and
 * files but no audio, so OpenAI models always run text-only.
 *
 * Requests are stateless (`store: false`): the session replays every output item, which keeps
 * encrypted reasoning and assistant `phase` values intact between runs.
 */
class OpenAiChatModel(
  private val apiKey: String,
  private val modelId: String,
  /** `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, or null for the model default. */
  private val reasoningEffort: String?,
  private val client: OkHttpClient = sharedHttpClient,
) : ChatModel {
  override val displayName: String = modelId
  override val kind: ModelKind = ModelKind.TEXT_ONLY

  override suspend fun openSession(spec: SessionSpec): ChatSession = OpenAiSession(this, spec)

  internal fun requestBody(spec: SessionSpec, input: List<JsonObject>): JsonObject =
    buildJsonObject {
      put("model", modelId)
      put("instructions", spec.systemPrompt)
      put("store", false)
      put("stream", true)
      putJsonArray("input") { input.forEach { add(it) } }
      if (spec.tools.isNotEmpty() || spec.nativeWebSearch) {
        putJsonArray("tools") {
          for (tool in spec.tools) {
            add(buildJsonObject {
              put("type", "function")
              put("name", tool.name)
              put("description", tool.description)
              put("parameters", tool.parametersJsonSchema())
              put("strict", false)
            })
          }
          if (spec.nativeWebSearch) add(buildJsonObject { put("type", "web_search") })
        }
      }
      reasoningEffort?.let { putJsonObject("reasoning") { put("effort", it) } }
    }

  internal suspend fun stream(
    body: JsonObject,
    callHolder: (Call) -> Unit,
    onEvent: (String) -> Boolean,
  ): StreamOutcome =
    postServerSentEvents(
      client = client,
      url = RESPONSES_URL,
      headers = mapOf("Authorization" to "Bearer $apiKey"),
      body = body,
      callHolder = callHolder,
      onEvent = onEvent,
    )
}

private class OpenAiSession(private val model: OpenAiChatModel, private val spec: SessionSpec) :
  ChatSession {
  private val history = mutableListOf<JsonObject>()
  private val activeCall = AtomicReference<Call?>()

  override suspend fun run(input: ModelInput, onText: (String) -> Unit): RunResult {
    val pending = buildList {
      for (result in input.toolResults) {
        add(buildJsonObject {
          put("type", "function_call_output")
          put("call_id", result.callId)
          put("output", result.content)
        })
      }
      input.user?.let { user ->
        val text =
          listOfNotNull(user.text, user.context).filter { it.isNotBlank() }.joinToString("\n\n")
        add(buildJsonObject {
          put("role", "user")
          putJsonArray("content") {
            add(buildJsonObject {
              put("type", "input_text")
              put("text", text)
            })
          }
        })
      }
    }
    val text = StringBuilder()
    var output: JsonArray? = null
    var failure: ModelError? = null
    val outcome =
      model.stream(model.requestBody(spec, history + pending), { activeCall.set(it) }) { data ->
        val event = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
        when (event?.get("type")?.jsonPrimitive?.contentOrNull) {
          "response.output_text.delta" -> {
            text.append(event["delta"]?.jsonPrimitive?.contentOrNull.orEmpty())
            onText(text.toString())
            true
          }
          "response.completed" -> {
            output = (event["response"] as? JsonObject)?.get("output") as? JsonArray
            false
          }
          "response.incomplete" -> {
            output = (event["response"] as? JsonObject)?.get("output") as? JsonArray
            false
          }
          "response.failed" -> {
            val error = ((event["response"] as? JsonObject)?.get("error") as? JsonObject)
            failure =
              ModelError.Failed(error?.get("message")?.jsonPrimitive?.contentOrNull ?: "Response failed")
            false
          }
          "error" -> {
            failure =
              ModelError.Failed(
                event["message"]?.jsonPrimitive?.contentOrNull
                  ?: (event["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                  ?: "Stream error"
              )
            false
          }
          else -> true
        }
      }
    activeCall.set(null)
    failure?.let { return RunResult.Error(it) }
    return when (outcome) {
      is StreamOutcome.Cancelled -> RunResult.Cancelled(text.toString())
      is StreamOutcome.Failed -> RunResult.Error(outcome.error)
      is StreamOutcome.Done -> {
        val items = output?.mapNotNull { it as? JsonObject }
          ?: return RunResult.Error(ModelError.Failed("Stream ended without a response"))
        history += pending
        history += items
        RunResult.Complete(items.toModelMessage())
      }
    }
  }

  override fun cancel() {
    activeCall.get()?.cancel()
  }
}

/** Visible text and function calls from a response's `output` items. */
internal fun List<JsonObject>.toModelMessage(): ModelMessage {
  val text = StringBuilder()
  val calls = mutableListOf<ModelToolCall>()
  for (item in this) {
    when (item["type"]?.jsonPrimitive?.contentOrNull) {
      "message" ->
        (item["content"] as? JsonArray)?.forEach { part ->
          val p = part as? JsonObject ?: return@forEach
          if (p["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
            if (text.isNotEmpty()) text.append(' ')
            text.append(p["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
          }
        }
      "function_call" ->
        calls +=
          ModelToolCall(
            id = item["call_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            args = parseArguments(item["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()),
          )
    }
  }
  return ModelMessage(text.toString(), calls)
}
