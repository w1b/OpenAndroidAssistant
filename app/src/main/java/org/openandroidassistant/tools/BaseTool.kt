/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools

/**
 * Convenience base for tools with a fixed schema. Its approval content is a plain description
 * of the call; tools whose default level is above NONE override it with a proper prompt.
 */
abstract class BaseTool : AssistantTool {
  abstract val schema: ToolSchema

  override fun schema(env: ToolEnvironment): ToolSchema = schema

  override fun approvalContent(call: ValidatedCall, context: ToolContext): ApprovalContent {
    val details =
      call.args.entries
        .filter { (k, v) -> v != null && k !in call.handles && k !in call.handleLists }
        .joinToString(", ") { (_, v) -> v.toString() }
    val targets =
      (call.handles.values + call.handleLists.values.flatten()).mapNotNull { describe(it.payload) }
    val target = (targets + listOfNotNull(details.takeIf { it.isNotEmpty() })).joinToString(", ")
    return ApprovalContent(
      spokenPrompt = if (target.isEmpty()) "${metadata.title}?" else "${metadata.title}: $target?",
      card = ApprovalCard(title = metadata.title, target = target),
    )
  }
}

/** A short human label for a result payload, used in approval prompts. */
internal fun describe(payload: Any?): String? =
  when (payload) {
    is org.openandroidassistant.orchestrator.RenderCard.Contact -> payload.name
    is org.openandroidassistant.orchestrator.RenderCard.Event -> payload.title
    is org.openandroidassistant.tools.impl.PlacePayload -> payload.card.name
    is org.openandroidassistant.orchestrator.RenderCard.Alarm -> payload.timeLabel
    is org.openandroidassistant.orchestrator.RenderCard.Reminder -> payload.text
    is org.openandroidassistant.orchestrator.RenderCard.Note -> payload.title
    is org.openandroidassistant.orchestrator.RenderCard.Message -> payload.from
    is org.openandroidassistant.tools.impl.AppEntry -> payload.label
    else -> null
  }
