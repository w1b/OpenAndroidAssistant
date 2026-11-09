/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaTest {
  @Test
  fun emitsRequiredListAndNumericBounds() {
    val schema =
      ToolSchema(
        name = "set_alarm",
        description = "Create an alarm.",
        params =
          listOf(
            ToolParam(
              name = "hour",
              type = ParamKind.INTEGER,
              description = "Local hour.",
              minimum = 0.0,
              maximum = 23.0,
            ),
            ToolParam(
              name = "label",
              type = ParamKind.STRING,
              description = "Optional label.",
              required = false,
            ),
          ),
      )

    val parameters = schema.toFunctionDeclarationJson().getAsJsonObject("parameters")
    val hour = parameters.getAsJsonObject("properties").getAsJsonObject("hour")
    assertEquals(0.0, hour.get("minimum").asDouble, 0.0)
    assertEquals(23.0, hour.get("maximum").asDouble, 0.0)
    assertEquals(listOf("hour"), parameters.getAsJsonArray("required").map { it.asString })
    assertFalse(parameters.getAsJsonObject("properties").getAsJsonObject("label").has("nullable"))
  }

  @Test
  fun rejectsInvalidToolAndParameterDeclarations() {
    assertThrows(IllegalArgumentException::class.java) {
      ToolSchema("Bad Name", "Description.", emptyList())
    }
    assertThrows(IllegalArgumentException::class.java) {
      ToolParam("choice", ParamKind.ENUM, "Choose.")
    }
    assertThrows(IllegalArgumentException::class.java) {
      ToolParam(
        "recipient",
        ParamKind.STRING,
        "Recipient.",
        handleType = HandleType.CONTACT,
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      ToolParam("query", ParamKind.STRING, "Search text.", minimum = 1.0)
    }
    assertThrows(IllegalArgumentException::class.java) {
      ToolParam(
        "window",
        ParamKind.ENUM,
        "Time window.",
        enumValues = listOf("today", "today"),
      )
    }
  }

  @Test
  fun handleTypeIsHostPolicyAndNotLeakedIntoModelSchema() {
    val param =
      ToolParam(
        "recipient",
        ParamKind.HANDLE,
        "A contact id.",
        handleType = HandleType.CONTACT,
      )
    val json = param.toSchemaJson()
    assertEquals("STRING", json.get("type").asString)
    assertTrue(!json.has("handleType"))
  }
}
