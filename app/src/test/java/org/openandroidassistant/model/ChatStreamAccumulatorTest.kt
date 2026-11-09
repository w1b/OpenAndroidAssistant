/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatStreamAccumulatorTest {
  private fun chunk(s: String) = json.parseToJsonElement(s).jsonObject

  @Test
  fun rebuildsTextToolCallsAndReasoning() {
    val acc = ChatStreamAccumulator()
    acc.accept(chunk("""{"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"Think","index":0,"format":"google-gemini-v1"}]}}]}"""))
    acc.accept(chunk("""{"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"ing","index":0}]}}]}"""))
    acc.accept(chunk("""{"choices":[{"delta":{"content":"Checking."}}]}"""))
    acc.accept(chunk("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"find_contact","arguments":"{\"query\":"}}]}}]}"""))
    acc.accept(chunk("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Harold\"}"}}]}}]}"""))
    val message = acc.message()
    assertEquals("Checking.", message.text)
    assertEquals(ModelToolCall("call_1", "find_contact", mapOf("query" to "Harold")), message.toolCalls.single())
    val assistant = acc.assistantMessage()
    val reasoning = (assistant["reasoning_details"] as JsonArray).single() as JsonObject
    assertEquals("Thinking", reasoning["text"]!!.jsonPrimitive.content)
    assertEquals("google-gemini-v1", reasoning["format"]!!.jsonPrimitive.content)
  }

  @Test
  fun responsesOutputItemsBecomeAMessage() {
    val items =
      json.parseToJsonElement(
        """[{"type":"reasoning","encrypted_content":"x"},{"type":"message","content":[{"type":"output_text","text":"Sent."}]},
           {"type":"function_call","call_id":"c9","name":"set_timer","arguments":"{\"seconds\":600}"}]"""
      ) as JsonArray
    val message = items.map { it as JsonObject }.toModelMessage()
    assertEquals("Sent.", message.text)
    assertEquals(ModelToolCall("c9", "set_timer", mapOf("seconds" to 600L)), message.toolCalls.single())
  }
}
