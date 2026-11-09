/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.openandroidassistant.tools.ParamKind
import org.openandroidassistant.tools.ToolParam
import org.openandroidassistant.tools.ToolSchema

internal val json = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
}

/** Standard JSON Schema for a tool's parameters, as OpenAI-style APIs expect. */
internal fun ToolSchema.parametersJsonSchema(): JsonObject = buildJsonObject {
  put("type", "object")
  putJsonObject("properties") { for (param in params) put(param.name, param.jsonSchema()) }
  val required = params.filter { it.required }.map { it.name }
  if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
}

private fun ToolParam.jsonSchema(): JsonObject = buildJsonObject {
  when (type) {
    ParamKind.STRING,
    ParamKind.HANDLE -> put("type", "string")
    ParamKind.INTEGER -> put("type", "integer")
    ParamKind.NUMBER -> put("type", "number")
    ParamKind.BOOLEAN -> put("type", "boolean")
    ParamKind.ENUM -> {
      put("type", "string")
      putJsonArray("enum") { enumValues.forEach { add(JsonPrimitive(it)) } }
    }
    ParamKind.HANDLE_LIST -> {
      put("type", "array")
      putJsonObject("items") { put("type", "string") }
    }
  }
  put("description", description)
  minimum?.let { put("minimum", it) }
  maximum?.let { put("maximum", it) }
}

/** Decodes a JSON value to plain Kotlin: String, Long, Double, Boolean, List, Map, or null. */
internal fun JsonElement.toPlain(): Any? =
  when (this) {
    is JsonNull -> null
    is JsonPrimitive ->
      when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        longOrNull != null -> longOrNull
        else -> doubleOrNull
      }
    is JsonArray -> map { it.toPlain() }
    is JsonObject -> mapValues { it.value.toPlain() }
  }

/** Parses a tool call's argument string. Malformed or non-object arguments decode to empty. */
internal fun parseArguments(raw: String): Map<String, Any?> {
  if (raw.isBlank()) return emptyMap()
  return runCatching {
      @Suppress("UNCHECKED_CAST")
      (json.parseToJsonElement(raw) as? JsonObject)?.toPlain() as? Map<String, Any?>
    }
    .getOrNull()
    .orEmpty()
}

/** Encodes plain Kotlin values back to JSON. */
internal fun Any?.toJsonElement(): JsonElement =
  when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> buildJsonObject { forEach { (k, v) -> put(k.toString(), v.toJsonElement()) } }
    is Iterable<*> -> buildJsonArray { forEach { add(it.toJsonElement()) } }
    else -> JsonPrimitive(toString())
  }
