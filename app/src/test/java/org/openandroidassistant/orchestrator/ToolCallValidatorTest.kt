/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openandroidassistant.tools.HandleType
import org.openandroidassistant.tools.ParamKind
import org.openandroidassistant.tools.ToolParam
import org.openandroidassistant.tools.ToolSchema

class ToolCallValidatorTest {
  private val handles = HandleTable()

  @Test
  fun rejectsUnknownArguments() {
    val result =
      validate(
        params = listOf(ToolParam("query", ParamKind.STRING, "Search text.")),
        args = mapOf("query" to "Ada", "surprise" to true),
      )
    assertTrue(result is ToolCallValidator.Result.Invalid)
  }

  @Test
  fun rejectsFractionalIntegerInsteadOfSilentlyRounding() {
    val result =
      validate(
        params =
          listOf(
            ToolParam(
              "minute",
              ParamKind.INTEGER,
              "Minute.",
              minimum = 0.0,
              maximum = 59.0,
            )
          ),
        args = mapOf("minute" to 4.5),
      )
    assertTrue(result is ToolCallValidator.Result.Invalid)
  }

  @Test
  fun rejectsIntegerOutsideDeclaredBounds() {
    val result =
      validate(
        params =
          listOf(
            ToolParam(
              "hour",
              ParamKind.INTEGER,
              "Hour.",
              minimum = 0.0,
              maximum = 23.0,
            )
          ),
        args = mapOf("hour" to 24),
      )
    assertTrue(result is ToolCallValidator.Result.Invalid)
  }

  @Test
  fun rejectsHandleOfWrongType() {
    val message = handles.mint(HandleType.MESSAGE, "42")
    val result =
      validate(
        params =
          listOf(
            ToolParam(
              "recipient",
              ParamKind.HANDLE,
              "A contact id.",
              handleType = HandleType.CONTACT,
            )
          ),
        args = mapOf("recipient" to message.handle),
      )
    assertTrue(result is ToolCallValidator.Result.Invalid)
  }

  @Test
  fun resolvesExpectedHandleType() {
    val contact = handles.mint(HandleType.CONTACT, "7")
    val result =
      validate(
        params =
          listOf(
            ToolParam(
              "recipient",
              ParamKind.HANDLE,
              "A contact id.",
              handleType = HandleType.CONTACT,
            )
          ),
        args = mapOf("recipient" to contact.handle),
      )
    assertTrue(result is ToolCallValidator.Result.Valid)
    assertEquals(
      contact,
      (result as ToolCallValidator.Result.Valid).call.handleArg("recipient"),
    )
  }

  @Test
  fun dropsExplicitNullsForOptionalArguments() {
    val result =
      validate(
        params = listOf(ToolParam("query", ParamKind.STRING, "Search text.", required = false)),
        args = mapOf("query" to null),
      )
    assertTrue(result is ToolCallValidator.Result.Valid)
  }

  @Test
  fun blankOptionalIdCountsAsLeftOut() {
    // get_directions with a place id and contact: "" (strict schemas make models fill every field).
    val place = handles.mint(HandleType.PLACE, "p1")
    val params =
      listOf(
        ToolParam("place", ParamKind.HANDLE, "Place.", required = false, handleType = HandleType.PLACE),
        ToolParam("contact", ParamKind.HANDLE, "Contact.", required = false, handleType = HandleType.CONTACT),
      )
    val result = validate(params, mapOf("place" to place.handle, "contact" to ""))
    assertTrue(result is ToolCallValidator.Result.Valid)
    assertEquals(null, (result as ToolCallValidator.Result.Valid).call.handles["contact"])
    // A required argument still can't be blank.
    assertTrue(validate(listOf(ToolParam("query", ParamKind.STRING, "Search text.")), mapOf("query" to " ")) is ToolCallValidator.Result.Invalid)
  }

  @Test
  fun resolvesHandleListsAndRejectsUnknownIds() {
    val contact = handles.mint(HandleType.CONTACT, "7")
    val params = listOf(ToolParam("items", ParamKind.HANDLE_LIST, "Ids."))
    val ok = validate(params, mapOf("items" to listOf(contact.handle)))
    assertEquals(listOf(contact), (ok as ToolCallValidator.Result.Valid).call.handleLists["items"])
    assertTrue(validate(params, mapOf("items" to listOf("c99"))) is ToolCallValidator.Result.Invalid)
  }

  private fun validate(params: List<ToolParam>, args: Map<String, Any?>): ToolCallValidator.Result =
    ToolCallValidator.validate(ToolSchema("test_tool", "Test a declaration.", params), args, handles)
}
