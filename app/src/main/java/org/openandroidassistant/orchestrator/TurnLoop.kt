/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.orchestrator

import android.util.Log
import com.google.gson.JsonObject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.openandroidassistant.model.ChatSession
import org.openandroidassistant.model.ModelError
import org.openandroidassistant.model.ModelInput
import org.openandroidassistant.model.ModelKind
import org.openandroidassistant.model.ModelToolCall
import org.openandroidassistant.model.RunResult
import org.openandroidassistant.model.ToolResult
import org.openandroidassistant.model.UserContent
import org.openandroidassistant.tools.ApprovalLevel
import org.openandroidassistant.tools.AssistantTool
import org.openandroidassistant.tools.Handoff
import org.openandroidassistant.tools.ParamKind
import org.openandroidassistant.tools.ToolContext
import org.openandroidassistant.tools.ToolOutcome
import org.openandroidassistant.tools.ToolSchema
import org.openandroidassistant.tools.ValidatedCall

private const val TAG = "TurnLoop"

/** Silence while a tool runs before the app speaks the tool's fixed progress phrase. */
private const val PROGRESS_PHRASE_DELAY_MS = 2_500L
/** Empty messages (no text, no tools) in a row before the app gives up on the turn. */
private const val MAX_EMPTY_MESSAGES = 2
private const val EMPTY_MESSAGE_NUDGE =
  "(Your last message was empty. Reply to the user now; your text is spoken aloud.)"
internal const val GIVE_UP_PHRASE = "Sorry, I couldn't finish that."
private val INTERRUPTED_JSON =
  JsonObject().apply {
    addProperty("status", "interrupted")
    addProperty("note", "The user interrupted before this ran, so nothing was done.")
  }.toString()
// Results for show and wait_for_reply sent without words: from models that send them alone
// (Gemini) and from models that make one call per message (Granite) on their way to speaking.
internal const val SILENT_SHOW_NOTE =
  "Shown on screen. The user can't hear anything yet: your next message must have the words you " +
    "want to say. Don't call show again. If your words ask the user something, call " +
    "wait_for_reply with them."
internal const val SILENT_WAIT_NOTE =
  "Done: the microphone will open after you speak. The user can't hear anything yet: your next " +
    "message must have the words you want to say. Don't call wait_for_reply again."

/** What the turn loop asks of the app around it: the screen, the speaker, approvals. */
interface TurnHost {
  /** A new model run started ("Thinking"). */
  fun onRunStarted()

  /** Cumulative visible text of the message being generated. */
  fun onPartialText(text: String)

  /** The message finished; [text] is its final visible text, possibly empty. */
  fun onMessageText(text: String)

  /** Queue [text] for speech after anything already queued. Doesn't wait. */
  fun say(text: String)

  /** Suspends until nothing is queued or playing. */
  suspend fun awaitSpeechIdle()

  fun onToolStarted(tool: AssistantTool, call: ValidatedCall)

  fun onToolFinished(tool: AssistantTool)

  /** Every tool call of a message and what the app answered, for the saved history. */
  fun onToolResult(call: ModelToolCall, result: ToolResult) {}

  /** Puts looked-up items and/or Markdown text on screen (`show`, or a completed action). */
  fun show(items: List<HandleEntry>, markdown: String?)

  /** Runs approval for one call. */
  suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision

  /** True when the phone is locked right now. */
  val isLocked: Boolean

  /** Asks the user to unlock; true once unlocked. */
  suspend fun requestUnlock(verb: String): Boolean
}

data class ApprovalRequest(
  val tool: AssistantTool,
  val call: ValidatedCall,
  val content: org.openandroidassistant.tools.ApprovalContent,
  val level: ApprovalLevel,
)

sealed interface ApprovalDecision {
  data object Approved : ApprovalDecision

  /**
   * Declined by tap, voice, or timeout. [reply] is what the user said instead, if anything: audio
   * for native-audio-in models plus a transcript, which is only ever used to pass a correction
   * along.
   */
  data class Declined(val replyAudio: org.openandroidassistant.runtime.AudioSegment?, val replyTranscript: String?) :
    ApprovalDecision
}

/**
 * The turn loop. Each model run produces one message with text, tool calls, or both.
 * Text is spoken; tool calls go through approval and run, and their results go back to the model
 * for another run. The turn ends on a message that has text and only calls tools whose result the
 * model doesn't need (`show`, `wait_for_reply`).
 *
 * One instance lives as long as its conversation, because results for the last message's `show`
 * and `wait_for_reply` calls are owed to the model before the next user message.
 */
class TurnLoop(
  private val session: ChatSession,
  private val modelKind: ModelKind,
  /** Tools by name, as exposed to the model at conversation start. */
  private val tools: Map<String, AssistantTool>,
  /** The exact schemas the model was given, for validation. */
  private val schemas: Map<String, ToolSchema>,
  private val executor: ToolExecutor,
  private val toolContext: ToolContext,
  private val approvalLevel: (AssistantTool) -> ApprovalLevel,
  private val allowedWhenLocked: (AssistantTool) -> Boolean,
  private val blockActions: Boolean = false,
) {
  sealed interface Outcome {
    /**
     * The turn ended. [waitForReply]: reopen the mic after speech. [handoff]: launch this after
     * speech and end the conversation.
     */
    data class Ended(val waitForReply: Boolean, val handoff: Handoff? = null) : Outcome

    data class Failed(val error: ModelError) : Outcome
  }

  /** Results owed for the previous turn's final message. */
  private var owedResults: List<ToolResult> = emptyList()

  /** Side effects that completed, for the interruption note. */
  val completedActions = mutableListOf<String>()

  /**
   * Runs one user turn to its end. Interruption is coroutine cancellation: the caller cancels
   * the job, and any committed action still finishes.
   */
  suspend fun runTurn(host: TurnHost, user: UserContent): Outcome {
    // The provider's history only grows when a run completes, so whatever is unanswered when an
    // interruption lands is owed to the model before the next user message.
    var owedOnCancel: List<ToolResult> = owedResults
    owedResults = emptyList()
    try {
      return turn(host, user, owedOnCancel) { owedOnCancel = it }
    } catch (e: kotlinx.coroutines.CancellationException) {
      owedResults = owedOnCancel
      throw e
    }
  }

  private suspend fun turn(
    host: TurnHost,
    user: UserContent,
    owed: List<ToolResult>,
    trackOwed: (List<ToolResult>) -> Unit,
  ): Outcome {
    var input = ModelInput(toolResults = owed, user = user)
    var emptyMessages = 0
    var waitForReply = false

    while (true) {
      trackOwed(input.toolResults)
      host.onRunStarted()
      var result = session.run(input, host::onPartialText)
      // Providers occasionally abort a request before any output; one quiet retry fixes most.
      if (result is RunResult.Error && result.error is ModelError.Failed) {
        Log.w(TAG, "run failed (${result.error.message}); retrying once")
        result = session.run(input, host::onPartialText)
      }
      val message =
        when (result) {
          is RunResult.Complete -> result.message
          is RunResult.Cancelled -> throw kotlinx.coroutines.CancellationException("Run cancelled")
          is RunResult.Error -> return Outcome.Failed(result.error)
        }
      val text = message.text.trim()
      host.onMessageText(text)
      Log.i(TAG, "message text=${text.length} chars calls=${message.toolCalls.map { it.name }}")
      if (org.openandroidassistant.BuildConfig.DEBUG) {
        Log.d(TAG, "model said: \"$text\"")
        message.toolCalls.forEach { Log.d(TAG, "model called: ${it.name} ${it.args}") }
      }

      if (message.toolCalls.isEmpty()) {
        if (text.isNotEmpty()) return Outcome.Ended(waitForReply)
        // A model can never end its turn without saying something.
        if (++emptyMessages > MAX_EMPTY_MESSAGES) {
          host.say(GIVE_UP_PHRASE)
          return Outcome.Ended(waitForReply = false)
        }
        input = ModelInput(user = UserContent(context = EMPTY_MESSAGE_NUDGE))
        continue
      }
      emptyMessages = 0

      val results = mutableListOf<ToolResult>()
      // If interrupted while these calls run, each still needs a result.
      trackOwed(message.toolCalls.map { ToolResult(it.id, it.name, INTERRUPTED_JSON) })
      var followUp: UserContent? = null
      var needsResults = false
      var handoff: Handoff? = null
      // Some models (Gemini) send show or wait_for_reply on their own and talk in the next run.
      // Their results then say plainly that the user is still waiting for words.
      val silent = text.isEmpty()
      // Ids minted by a call in this message weren't seen by the model when it wrote the message.
      val seenHandles = toolContext.handleTable.issued()
      for (call in message.toolCalls) {
        val unseen = unseenHandles(call, seenHandles)
        if (unseen.isNotEmpty()) {
          needsResults = true
          results += ToolResult(call.id, call.name, errorJson(unseenHandleInstruction(unseen)))
          continue
        }
        when (call.name) {
          SHOW_TOOL -> results += show(host, call, silent)
          WAIT_FOR_REPLY_TOOL -> {
            waitForReply = true
            results +=
              ToolResult(
                call.id,
                call.name,
                okJson(
                  if (silent) SILENT_WAIT_NOTE
                  else "The microphone opens after you finish speaking."
                ),
              )
          }
          else -> {
            needsResults = true
            val ran = runTool(host, call)
            results += ran.result
            trackOwed(message.toolCalls.map { c -> results.firstOrNull { it.callId == c.id } ?: ToolResult(c.id, c.name, INTERRUPTED_JSON) })
            ran.followUp?.let { followUp = it }
            ran.handoff?.let { handoff = it }
          }
        }
      }

      for (call in message.toolCalls) results.firstOrNull { it.callId == call.id }?.let { host.onToolResult(call, it) }
      if (handoff != null) {
        // open_app and friends end the conversation: the model's text, else the tool's phrase.
        if (text.isEmpty()) host.say(handoff.phrase)
        owedResults = results
        return Outcome.Ended(waitForReply = false, handoff = handoff)
      }
      if (text.isNotEmpty() && !needsResults) {
        owedResults = results
        return Outcome.Ended(waitForReply)
      }
      input = ModelInput(toolResults = results, user = followUp)
    }
  }

  /** Handle arguments that exist now but didn't when the model wrote this message: guesses. */
  private fun unseenHandles(call: ModelToolCall, seen: Set<String>): List<String> {
    val schema = schemas[call.name] ?: return emptyList()
    return schema.params
      .flatMap { p ->
        when (p.type) {
          ParamKind.HANDLE -> listOfNotNull(call.args[p.name] as? String)
          ParamKind.HANDLE_LIST -> (call.args[p.name] as? List<*>)?.filterIsInstance<String>().orEmpty()
          else -> emptyList()
        }
      }
      .filter { it !in seen && toolContext.handleTable.resolve(it) != null }
  }

  private fun show(host: TurnHost, call: ModelToolCall, silent: Boolean): ToolResult {
    val schema = schemas[SHOW_TOOL] ?: return ToolResult(call.id, call.name, errorJson("show isn't available."))
    return when (val v = ToolCallValidator.validate(schema, call.args, toolContext.handleTable)) {
      is ToolCallValidator.Result.Invalid -> ToolResult(call.id, call.name, errorJson(v.instruction))
      is ToolCallValidator.Result.Valid -> {
        val items = v.call.handleLists["items"].orEmpty().filter { it.payload != null }
        val markdown = v.call.stringArg("text")
        if (items.isEmpty() && markdown == null) {
          ToolResult(call.id, call.name, errorJson("Nothing to show: pass item ids, text, or both."))
        } else {
          host.show(items, markdown)
          ToolResult(
            call.id,
            call.name,
            okJson(if (silent) SILENT_SHOW_NOTE else "Shown."),
          )
        }
      }
    }
  }

  private class Ran(val result: ToolResult, val followUp: UserContent? = null, val handoff: Handoff? = null)

  private suspend fun runTool(host: TurnHost, call: ModelToolCall): Ran {
    fun reply(json: String) = Ran(ToolResult(call.id, call.name, json))

    val tool = tools[call.name] ?: return reply(errorJson("There is no tool named '${call.name}'. Use only the tools you were given."))
    val schema = schemas.getValue(call.name)
    val validated =
      when (val v = ToolCallValidator.validate(schema, call.args, toolContext.handleTable)) {
        is ToolCallValidator.Result.Valid -> v.call
        is ToolCallValidator.Result.Invalid -> return reply(errorJson(v.instruction))
      }

    if (blockActions && !tool.metadata.cancellable) {
      Log.w(TAG, "Debug safety mode blocked ${tool.metadata.id} ${validated.args}")
      return reply(
        errorJson("Debug safety mode blocked ${tool.metadata.id}; it was intentionally not run. Tell the user.")
      )
    }

    // Lock screen: ask to unlock, then continue.
    if (host.isLocked && !allowedWhenLocked(tool)) {
      val unlocked = host.requestUnlock(tool.metadata.unlockVerb ?: "do that")
      if (!unlocked) {
        return reply(
          declinedJson("The phone is locked and the user didn't unlock it, so nothing was done.")
        )
      }
    }

    val level = approvalLevel(tool)
    if (level != ApprovalLevel.NONE) {
      // The approval prompt follows whatever the model said first.
      host.awaitSpeechIdle()
      val content = tool.approvalContent(validated, toolContext)
      when (val decision = host.requestApproval(ApprovalRequest(tool, validated, content, level))) {
        ApprovalDecision.Approved -> Unit
        is ApprovalDecision.Declined -> {
          val transcript = decision.replyTranscript?.takeIf { it.isNotBlank() }
          val audio = decision.replyAudio.takeIf { modelKind == ModelKind.AUDIO_IN }
          val note =
            when {
              audio != null -> "The user declined, so nothing was done. Their reply follows. $CORRECTION_NOTE"
              transcript != null -> "The user declined, so nothing was done. They said: \"$transcript\" $CORRECTION_NOTE"
              else -> "The user declined, so nothing was done."
            }
          return Ran(
            result = ToolResult(call.id, call.name, declinedJson(note)),
            followUp = audio?.let { UserContent(audio = it, context = "(The user's reply to the approval prompt.)") },
          )
        }
      }
    }

    host.onToolStarted(tool, validated)
    val execution =
      try {
        coroutineScope {
          val phrase = tool.metadata.progressPhrase
          val progress =
            if (phrase != null) {
              launch {
                host.awaitSpeechIdle()
                delay(PROGRESS_PHRASE_DELAY_MS)
                host.say(phrase)
              }
            } else null
          try {
            executor.run(tool, validated, toolContext)
          } finally {
            progress?.cancel()
          }
        }
      } finally {
        host.onToolFinished(tool)
      }

    if (execution.outcome is ToolOutcome.Success && !tool.metadata.cancellable) {
      completedActions += tool.metadata.title
    }
    if (execution.autoShown.isNotEmpty()) host.show(execution.autoShown, null)
    val handoff = (execution.outcome as? ToolOutcome.Success)?.handoff
    if (org.openandroidassistant.BuildConfig.DEBUG) Log.d(TAG, "result ${call.name}: ${execution.envelopeJson.take(600)}")
    return Ran(ToolResult(call.id, call.name, execution.envelopeJson), handoff = handoff)
  }

  companion object {
    const val SHOW_TOOL = "show"
    const val WAIT_FOR_REPLY_TOOL = "wait_for_reply"
  }
}

internal const val CORRECTION_NOTE =
  "If they said what to change, make the corrected call now; the app asks them to approve it. Don't ask them yourself."

internal fun unseenHandleInstruction(unseen: List<String>): String =
  "${unseen.joinToString()} came from a result in this same message, so you hadn't seen it yet. " +
    "Nothing was done. Read that result, then make this call in your next message if it's still right."

private fun okJson(note: String): String =
  JsonObject().apply {
    addProperty("status", "ok")
    addProperty("note", note)
  }.toString()

private fun errorJson(instruction: String): String =
  JsonObject().apply {
    addProperty("status", "error")
    addProperty("instruction", instruction)
  }.toString()

private fun declinedJson(note: String): String =
  JsonObject().apply {
    addProperty("status", "declined")
    addProperty("note", note)
  }.toString()
