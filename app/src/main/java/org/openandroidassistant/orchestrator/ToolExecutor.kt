/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.orchestrator

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.openandroidassistant.tools.AssistantTool
import org.openandroidassistant.tools.ResultItem
import org.openandroidassistant.tools.ToolContext
import org.openandroidassistant.tools.ToolOutcome
import org.openandroidassistant.tools.ValidatedCall

private const val TAG = "ToolExecutor"

/** Per-call cap on returned items; longer results are truncated with a note. */
private const val ITEM_CAP = 10

internal const val SHOW_HINT =
  "To put any of these on screen, call show with their ids in the message where you talk about them."
private const val TOOL_TIMEOUT_MS = 30_000L

/**
 * Runs approved tool calls and renders outcomes into the result envelope
 * `{status, items, has_more}`. Errors and truncation notes are written as instructions, because
 * tool output is prompt.
 */
class ToolExecutor {

  /**
   * Read-only tools are cancelled with the caller (an interruption); tools with effects run to
   * completion on [NonCancellable], so a committed action is never half done.
   */
  suspend fun run(tool: AssistantTool, call: ValidatedCall, context: ToolContext): ExecutionResult {
    val outcome =
      try {
        if (tool.metadata.cancellable) {
          withTimeout(TOOL_TIMEOUT_MS) { tool.execute(call, context) }
        } else {
          withContext(NonCancellable) { withTimeout(TOOL_TIMEOUT_MS) { tool.execute(call, context) } }
        }
      } catch (e: TimeoutCancellationException) {
        Log.w(TAG, "Tool ${tool.metadata.id} timed out")
        ToolOutcome.Steering(
          status = "error",
          instruction = "${tool.metadata.id} took too long and was stopped. Tell the user it didn't complete.",
        )
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Tool ${tool.metadata.id} failed", e)
        ToolOutcome.Steering(
          status = "error",
          instruction =
            "${tool.metadata.id} failed (${e.message ?: e::class.simpleName}). Don't retry with " +
              "the same arguments. Tell the user what you couldn't do.",
        )
      }
    return render(outcome, context)
  }

  /** Renders an outcome to envelope JSON, minting result ids for every item. */
  fun render(outcome: ToolOutcome, context: ToolContext): ExecutionResult =
    when (outcome) {
      is ToolOutcome.Steering ->
        ExecutionResult(
          outcome = outcome,
          envelopeJson =
            JsonObject()
              .apply {
                addProperty("status", outcome.status)
                addProperty("instruction", outcome.instruction)
              }
              .toString(),
          autoShown = emptyList(),
        )
      is ToolOutcome.Success -> {
        val truncated = outcome.items.size > ITEM_CAP
        val visible = outcome.items.take(ITEM_CAP)
        val autoShown = mutableListOf<HandleEntry>()
        val json =
          JsonObject().apply {
            addProperty("status", "ok")
            if (outcome.text != null) addProperty("text", outcome.text)
            if (visible.isNotEmpty() || outcome.text == null) {
              add(
                "items",
                JsonArray().apply {
                  visible.forEach { item ->
                    val (itemJson, entry) = item.toJson(context)
                    add(itemJson)
                    if (item.autoShow && entry.payload != null) autoShown += entry
                  }
                },
              )
            }
            if (outcome.hasMore || truncated) addProperty("has_more", true)
            val notes = mutableListOf<String>()
            if (truncated) {
              notes +=
                "Only the first $ITEM_CAP of ${outcome.items.size} results are listed. Narrow " +
                  "the search if the right one isn't here."
            }
            outcome.extras?.entrySet()?.forEach { (k, v) -> if (k == "note") notes += v.asString else add(k, v) }
            // Lookups: remind the model that the items it talks about go on screen.
            if (visible.any { it.type.renderable && !it.autoShow }) notes += SHOW_HINT
            if (notes.isNotEmpty()) addProperty("note", notes.joinToString(" "))
          }
        ExecutionResult(outcome, json.toString(), autoShown)
      }
    }

  private fun ResultItem.toJson(context: ToolContext): Pair<JsonObject, HandleEntry> {
    val entry = context.handleTable.mint(type, nativeId, renderPayload)
    val json =
      JsonObject().apply {
        addProperty("id", entry.handle)
        for ((k, v) in projection) {
          when (v) {
            null -> {}
            is String -> if (v.isNotEmpty()) addProperty(k, v)
            is Number -> addProperty(k, v)
            is Boolean -> addProperty(k, v)
            is List<*> -> add(k, JsonArray().apply { v.forEach { add(it?.toString()) } })
            else -> add(k, JsonPrimitive(v.toString()))
          }
        }
      }
    return json to entry
  }
}

data class ExecutionResult(
  val outcome: ToolOutcome,
  val envelopeJson: String,
  /** Completed actions' own results, displayed without `show`. */
  val autoShown: List<HandleEntry>,
)
