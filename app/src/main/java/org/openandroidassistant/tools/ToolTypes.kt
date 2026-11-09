/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools

import com.google.gson.JsonObject
import org.openandroidassistant.permissions.AppCapability

/** How a tool call is approved before it runs. The model never decides this. */
enum class ApprovalLevel(val title: String, val description: String) {
  NONE("None", "Runs right away"),
  LOW("Low", "Say yes or tap"),
  HIGH("High", "Tap to approve"),
  CRITICAL("Critical", "Tap, then confirm with your fingerprint or screen lock"),
}

/**
 * Object types behind result ids. The prefix makes ids type-prefixed (`m3`, `c1`); [renderable]
 * marks the types that can appear on screen.
 */
enum class HandleType(val prefix: String, val renderable: Boolean) {
  MESSAGE("m", renderable = true),
  CONTACT("c", renderable = true),
  EVENT("e", renderable = true),
  ALARM("a", renderable = true),
  TIMER("t", renderable = true),
  REMINDER("r", renderable = true),
  NOTE("n", renderable = true),
  APP("app", renderable = false),
  WEATHER("w", renderable = true),
  PLACE("p", renderable = true),
  NOTIFICATION("nt", renderable = true),
  MEDIA("md", renderable = true),
  WEB("web", renderable = true),
}

/** Host-side per-tool metadata. */
data class ToolMetadata(
  /** Registry key; equals the schema name the model sees. */
  val id: String,
  /** Human-facing name for settings. */
  val title: String = id,
  val summary: String = "",
  val category: ToolCategory = ToolCategory.ESSENTIALS,
  /** Default approval level; the user can set any tool to any level. */
  val defaultApproval: ApprovalLevel = ApprovalLevel.NONE,
  /** Read-only calls are cancelled with the turn; calls with effects run to completion. */
  val cancellable: Boolean = true,
  /** Default for "may run while the phone is locked"; the user can override per tool. */
  val allowedWhenLockedDefault: Boolean = false,
  /** Missing required capability → tool absent from the model's tool list. */
  val requiredCapabilities: List<AppCapability> = emptyList(),
  /** Improves a tool but does not gate it. */
  val optionalCapabilities: List<AppCapability> = emptyList(),
  /** Fixed phrase spoken when a few seconds pass silently while the tool runs ("Checking the weather…"). */
  val progressPhrase: String? = null,
  /** Verb phrase for the unlock prompt: "Unlock your phone so I can {verb}." */
  val unlockVerb: String? = null,
  /** Running it hands the user off to another app, which ends the conversation. */
  val endsConversation: Boolean = false,
  /** Exposure depends on a setting or provider being configured (web search, Firecrawl…). */
  val requiresConfiguration: Boolean = false,
  /** What allowing it while locked would let anyone holding the phone do; settings warn about it. */
  val lockedRisk: LockedRisk? = null,
)

enum class LockedRisk {
  /** Calls, texts, calendar changes: done in the user's name. */
  ACTS_AS_YOU,
  /** Messages, notifications, contacts, calendar, notes: read aloud to whoever asks. */
  READS_PRIVATE,
}

enum class ToolCategory(val title: String) {
  COMMUNICATION("Calls and messages"),
  PLANNING("Calendar, reminders, and clock"),
  INFORMATION("Information"),
  DEVICE("Phone and apps"),
  ESSENTIALS("Essentials"),
}

/** Outcome of executing (or refusing) a tool call. Tool output is prompt. */
sealed interface ToolOutcome {
  /** Uniform result envelope `{status, items, has_more}`. */
  data class Success(
    val items: List<ResultItem>,
    val hasMore: Boolean = false,
    /** Optional scalar extras merged into the envelope. */
    val extras: JsonObject? = null,
    /** Plain-text result for tools whose output isn't a list of items (web pages). */
    val text: String? = null,
    /** Set by tools that hand the user off to another app, which ends the conversation. */
    val handoff: Handoff? = null,
  ) : ToolOutcome

  /**
   * Errors, empty results, refusals: written as instructions for what to do next, because tool
   * output is functionally prompt.
   */
  data class Steering(val status: String, val instruction: String) : ToolOutcome
}

/**
 * One item in a tool result. The executor mints a result id for it; the compact [projection]
 * goes to the model and [renderPayload] stays in the app for display.
 */
data class ResultItem(
  val type: HandleType,
  /** Persistent native identifier (SMS row id, contact id, event id…). */
  val nativeId: String,
  /** Compact model-facing fields. The result id is injected by the executor as "id". */
  val projection: Map<String, Any?>,
  /** Full object for display; null when the type has nothing to show. */
  val renderPayload: Any? = null,
  /**
   * A completed action's own result (sent message, running timer, new alarm). It appears on
   * screen without the model calling `show`.
   */
  val autoShow: Boolean = false,
)

/**
 * Launch [intent] once the assistant finishes speaking, then close the overlay.
 * [phrase] is spoken when the model said nothing, e.g. "Opening Spotify…".
 */
data class Handoff(val phrase: String, val intent: android.content.Intent)
