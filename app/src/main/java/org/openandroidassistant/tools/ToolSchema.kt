/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * The model-facing contract of one tool: flat arguments only (scalars, enums, result ids). The
 * same schema is sent to the model and used to validate its calls.
 */
data class ToolSchema(
  val name: String,
  val description: String,
  val params: List<ToolParam>,
) {
  init {
    require(name.matches(Regex("[a-z][a-z0-9_]{1,63}"))) {
      "Tool name '$name' must be short snake_case."
    }
    require(description.isNotBlank()) { "Tool '$name' needs a model-facing description." }
    require(params.map { it.name }.distinct().size == params.size) {
      "Tool '$name' declares a parameter more than once."
    }
  }

  /** Gemini-style function declaration JSON, the shape Gemma's tool template renders (LiteRT-LM). */
  fun toFunctionDeclarationJson(): JsonObject {
    val properties = JsonObject()
    val required = JsonArray()
    for (param in params) {
      properties.add(param.name, param.toSchemaJson())
      if (param.required) required.add(param.name)
    }
    return JsonObject().apply {
      addProperty("name", name)
      addProperty("description", description)
      add(
        "parameters",
        JsonObject().apply {
          addProperty("type", "OBJECT")
          add("properties", properties)
          if (!required.isEmpty) add("required", required)
        },
      )
    }
  }
}

data class ToolParam(
  val name: String,
  val type: ParamKind,
  val description: String,
  val required: Boolean = true,
  /** For [ParamKind.ENUM]. */
  val enumValues: List<String> = emptyList(),
  /** Required result type. A contact id must never be accepted where a message id is expected. */
  val handleType: HandleType? = null,
  /** For [ParamKind.HANDLE_LIST]: the accepted types, or empty for any renderable item. */
  val handleTypes: Set<HandleType> = emptySet(),
  /** Inclusive numeric constraints, enforced by validation. */
  val minimum: Double? = null,
  val maximum: Double? = null,
) {
  init {
    require(name.matches(Regex("[a-z][a-z0-9_]{0,63}"))) {
      "Parameter name '$name' must be snake_case."
    }
    require(description.isNotBlank()) { "Parameter '$name' needs a description." }
    require(type == ParamKind.ENUM || enumValues.isEmpty()) {
      "Only enum parameters may declare enum values."
    }
    require(type != ParamKind.ENUM || enumValues.isNotEmpty()) {
      "Enum parameter '$name' must declare at least one value."
    }
    require(enumValues.all { it.isNotBlank() } && enumValues.distinct().size == enumValues.size) {
      "Enum parameter '$name' must declare unique, non-blank values."
    }
    require(type == ParamKind.HANDLE || handleType == null) {
      "Only result-id parameters may declare a result type."
    }
    require(
      (minimum == null && maximum == null) ||
        type == ParamKind.INTEGER ||
        type == ParamKind.NUMBER
    ) {
      "Only numeric parameters may declare a range."
    }
    require(minimum == null || maximum == null || minimum <= maximum) {
      "Parameter '$name' has an invalid numeric range."
    }
  }

  fun toSchemaJson(): JsonObject =
    JsonObject().apply {
      when (type) {
        ParamKind.STRING,
        ParamKind.HANDLE -> addProperty("type", "STRING")
        ParamKind.INTEGER -> addProperty("type", "INTEGER")
        ParamKind.NUMBER -> addProperty("type", "NUMBER")
        ParamKind.BOOLEAN -> addProperty("type", "BOOLEAN")
        ParamKind.ENUM -> {
          addProperty("type", "STRING")
          add("enum", JsonArray().apply { enumValues.forEach(::add) })
        }
        ParamKind.HANDLE_LIST -> {
          addProperty("type", "ARRAY")
          add("items", JsonObject().apply { addProperty("type", "STRING") })
        }
      }
      addProperty("description", description)
      minimum?.let { addProperty("minimum", it) }
      maximum?.let { addProperty("maximum", it) }
    }
}

enum class ParamKind {
  STRING,
  INTEGER,
  NUMBER,
  BOOLEAN,
  ENUM,
  /** A result id issued by an earlier tool call in this conversation. */
  HANDLE,
  /** A list of result ids. */
  HANDLE_LIST,
}
