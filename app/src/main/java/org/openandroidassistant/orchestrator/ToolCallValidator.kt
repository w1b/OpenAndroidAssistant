/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.orchestrator

import kotlin.math.floor
import org.openandroidassistant.tools.ParamKind
import org.openandroidassistant.tools.ToolSchema
import org.openandroidassistant.tools.ValidatedCall

/**
 * Validates a model's tool call against the tool's schema before anything runs. Unknown
 * arguments, fractional integers, out-of-range values and wrong result types all fail closed,
 * with an instruction the model can act on.
 */
object ToolCallValidator {
  sealed interface Result {
    data class Valid(val call: ValidatedCall) : Result

    data class Invalid(val instruction: String) : Result
  }

  fun validate(schema: ToolSchema, args: Map<String, Any?>, handleTable: HandleTable): Result {
    val declaredNames = schema.params.mapTo(hashSetOf()) { it.name }
    // Models sometimes send explicit nulls for omitted optional arguments.
    val present = args.filterValues { it != null }
    val unexpected = present.keys - declaredNames
    if (unexpected.isNotEmpty()) {
      return invalid(
        "${schema.name} does not accept ${unexpected.joinToString { "'$it'" }}. " +
          "Call it again using only: ${declaredNames.sorted().joinToString().ifEmpty { "no arguments" }}."
      )
    }

    val coerced = HashMap<String, Any?>()
    val handles = HashMap<String, HandleEntry>()
    val handleLists = HashMap<String, List<HandleEntry>>()
    for (param in schema.params) {
      // Strict schemas list every argument as required, so models fill the ones they mean to leave
      // out with "" (get_directions got contact: "" next to a place id). Blank optional = absent.
      val raw = present[param.name]?.takeUnless { !param.required && it is String && it.isBlank() }
      if (raw == null) {
        if (param.required) {
          return invalid(
            "The call to ${schema.name} is missing '${param.name}'. Call it again with that argument."
          )
        }
        continue
      }

      when (param.type) {
        ParamKind.STRING -> {
          val value =
            raw as? String
              ?: return invalid("'${param.name}' of ${schema.name} must be text.")
          if (param.required && value.isBlank()) {
            return invalid("'${param.name}' of ${schema.name} cannot be blank.")
          }
          coerced[param.name] = value
        }
        ParamKind.ENUM -> {
          val value =
            raw as? String
              ?: return invalid("'${param.name}' of ${schema.name} must be one of the listed choices.")
          if (value !in param.enumValues) {
            return invalid(
              "'$value' is not valid for '${param.name}'. Use: ${param.enumValues.joinToString()}."
            )
          }
          coerced[param.name] = value
        }
        ParamKind.INTEGER -> {
          val value =
            when (raw) {
              is Byte,
              is Short,
              is Int,
              is Long -> (raw as Number).toLong()
              is Float -> raw.toDouble().takeIf { it.isFinite() && floor(it) == it }?.toLong()
              is Double -> raw.takeIf { it.isFinite() && floor(it) == it }?.toLong()
              is String -> raw.toLongOrNull()
              else -> null
            }
              ?: return invalid("'${param.name}' of ${schema.name} must be a whole number.")
          if (!withinBounds(value.toDouble(), param.minimum, param.maximum)) {
            return invalid(rangeInstruction(schema.name, param.name, param.minimum, param.maximum))
          }
          coerced[param.name] = value
        }
        ParamKind.NUMBER -> {
          val value =
            when (raw) {
              is Number -> raw.toDouble()
              is String -> raw.toDoubleOrNull()
              else -> null
            }?.takeIf { it.isFinite() }
              ?: return invalid("'${param.name}' of ${schema.name} must be a finite number.")
          if (!withinBounds(value, param.minimum, param.maximum)) {
            return invalid(rangeInstruction(schema.name, param.name, param.minimum, param.maximum))
          }
          coerced[param.name] = value
        }
        ParamKind.BOOLEAN -> {
          val value =
            when {
              raw is Boolean -> raw
              raw is String && raw.equals("true", ignoreCase = true) -> true
              raw is String && raw.equals("false", ignoreCase = true) -> false
              else -> null
            }
              ?: return invalid("'${param.name}' of ${schema.name} must be true or false.")
          coerced[param.name] = value
        }
        ParamKind.HANDLE -> {
          val handle =
            raw as? String
              ?: return invalid("'${param.name}' of ${schema.name} must be a result id.")
          val entry =
            handleTable.resolve(handle)
              ?: return invalid(
                "'$handle' is not an id from this conversation. Search first and use an id from that result."
              )
          if (param.handleType != null && entry.type != param.handleType) {
            return invalid(
              "'$handle' is a ${entry.type.name.lowercase()} id, but '${param.name}' needs a " +
                "${param.handleType.name.lowercase()} id. Use the correct search tool first."
            )
          }
          coerced[param.name] = handle
          handles[param.name] = entry
        }
        ParamKind.HANDLE_LIST -> {
          val ids =
            (raw as? List<*>)?.map { it as? String ?: return invalid("'${param.name}' must be a list of result ids.") }
              ?: (raw as? String)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
              ?: return invalid("'${param.name}' of ${schema.name} must be a list of result ids.")
          val entries =
            ids.distinct().map { id ->
              val entry =
                handleTable.resolve(id)
                  ?: return invalid("'$id' is not an id from this conversation. Use ids from earlier results.")
              if (param.handleTypes.isNotEmpty() && entry.type !in param.handleTypes) {
                return invalid("'$id' can't be used for '${param.name}'.")
              }
              entry
            }
          coerced[param.name] = ids.distinct()
          handleLists[param.name] = entries
        }
      }
    }
    return Result.Valid(ValidatedCall(schema.name, coerced, handles, handleLists))
  }

  private fun withinBounds(value: Double, minimum: Double?, maximum: Double?): Boolean =
    (minimum == null || value >= minimum) && (maximum == null || value <= maximum)

  private fun rangeInstruction(
    tool: String,
    argument: String,
    minimum: Double?,
    maximum: Double?,
  ): String {
    val range =
      when {
        minimum != null && maximum != null -> "between ${minimum.pretty()} and ${maximum.pretty()}"
        minimum != null -> "at least ${minimum.pretty()}"
        else -> "at most ${checkNotNull(maximum).pretty()}"
      }
    return "'$argument' of $tool must be $range."
  }

  private fun Double.pretty(): String = if (floor(this) == this) toLong().toString() else toString()

  private fun invalid(message: String) = Result.Invalid(message)
}
