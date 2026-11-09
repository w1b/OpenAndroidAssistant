/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.orchestrator

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.openandroidassistant.model.ChatSession
import org.openandroidassistant.model.ModelInput
import org.openandroidassistant.model.ModelKind
import org.openandroidassistant.model.ModelMessage
import org.openandroidassistant.model.ModelToolCall
import org.openandroidassistant.model.RunResult
import org.openandroidassistant.model.UserContent
import org.openandroidassistant.orchestrator.RenderCard
import org.openandroidassistant.proto.AssistantSettings
import org.openandroidassistant.runtime.AudioSegment
import org.openandroidassistant.tools.ApprovalCard
import org.openandroidassistant.tools.ApprovalContent
import org.openandroidassistant.tools.ApprovalLevel
import org.openandroidassistant.tools.AssistantTool
import org.openandroidassistant.tools.HandleType
import org.openandroidassistant.tools.LocationProvider
import org.openandroidassistant.tools.ParamKind
import org.openandroidassistant.tools.ResultItem
import org.openandroidassistant.tools.ToolContext
import org.openandroidassistant.tools.ToolEnvironment
import org.openandroidassistant.tools.ToolMetadata
import org.openandroidassistant.tools.ToolOutcome
import org.openandroidassistant.tools.ToolParam
import org.openandroidassistant.tools.ToolSchema
import org.openandroidassistant.tools.ValidatedCall

class TurnLoopTest {

  /** Replies with scripted messages and records every input. */
  private class ScriptedSession(vararg messages: ModelMessage) : ChatSession {
    val script = ArrayDeque(messages.toList())
    val inputs = mutableListOf<ModelInput>()

    override suspend fun run(input: ModelInput, onText: (String) -> Unit): RunResult {
      inputs += input
      val next = script.removeFirst()
      if (next.text.isNotEmpty()) onText(next.text)
      return RunResult.Complete(next)
    }

    override fun cancel() {}
  }

  private class FakeTool(
    name: String,
    private val level: ApprovalLevel = ApprovalLevel.NONE,
    private val item: ResultItem? = null,
    params: List<ToolParam> = listOf(ToolParam("q", ParamKind.STRING, "Query.", required = false)),
  ) : AssistantTool {
    var runs = 0
    private val schema = ToolSchema(name, "A test tool.", params)
    override val metadata = ToolMetadata(id = name, title = name, defaultApproval = level, cancellable = false)

    override fun schema(env: ToolEnvironment) = schema

    override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
      runs++
      return ToolOutcome.Success(items = listOfNotNull(item))
    }

    override fun approvalContent(call: ValidatedCall, context: ToolContext) =
      ApprovalContent("Do it?", ApprovalCard("Do", "it"))
  }

  private class FakeHost(var decision: ApprovalDecision = ApprovalDecision.Approved) : TurnHost {
    val spoken = mutableListOf<String>()
    val messages = mutableListOf<String>()
    val shown = mutableListOf<HandleEntry>()
    var approvals = 0

    override fun onRunStarted() {}

    override fun onPartialText(text: String) {}

    override fun onMessageText(text: String) {
      if (text.isNotEmpty()) messages += text
    }

    override fun say(text: String) {
      spoken += text
    }

    override suspend fun awaitSpeechIdle() {}

    override fun onToolStarted(tool: AssistantTool, call: ValidatedCall) {}

    override fun onToolFinished(tool: AssistantTool) {}

    override fun show(items: List<HandleEntry>, markdown: String?) {
      shown += items
    }

    override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision {
      approvals++
      return decision
    }

    override val isLocked = false

    override suspend fun requestUnlock(verb: String) = true
  }

  private fun msg(text: String, vararg calls: Pair<String, Map<String, Any?>>) =
    ModelMessage(text, calls.mapIndexed { i, (name, args) -> ModelToolCall("call_$i", name, args) })

  private fun loop(session: ChatSession, vararg tools: AssistantTool, kind: ModelKind = ModelKind.AUDIO_IN): TurnLoop {
    val env = ToolEnvironment(locationAvailable = false, googleMapsInstalled = false)
    val schemas = tools.associate { it.metadata.id to it.schema(env) } +
      mapOf(TurnLoop.SHOW_TOOL to ConversationSpecBuilder.showSchema(), TurnLoop.WAIT_FOR_REPLY_TOOL to ConversationSpecBuilder.waitForReplySchema())
    return TurnLoop(
      session = session,
      modelKind = kind,
      tools = tools.associateBy { it.metadata.id },
      schemas = schemas,
      executor = ToolExecutor(),
      toolContext =
        ToolContext(mock(Context::class.java), AssistantSettings.getDefaultInstance(), HandleTable(), object : LocationProvider {
          override suspend fun current() = null
        }),
      approvalLevel = { it.metadata.defaultApproval },
      allowedWhenLocked = { true },
    )
  }

  private val user = UserContent(text = "hi")

  @Test
  fun textOnlyMessageEndsTheTurn() = runTest {
    val session = ScriptedSession(msg("It's 4:15."))
    val outcome = loop(session).runTurn(FakeHost(), user)
    assertEquals(TurnLoop.Outcome.Ended(waitForReply = false), outcome)
    assertEquals(1, session.inputs.size)
  }

  @Test
  fun toolResultsGoBackToTheModel() = runTest {
    val tool = FakeTool("find_contact", item = ResultItem(HandleType.CONTACT, "7", mapOf("name" to "Harold")))
    val session = ScriptedSession(msg("", "find_contact" to mapOf("q" to "Harold")), msg("Found Harold."))
    loop(session, tool).runTurn(FakeHost(), user)
    assertEquals(1, tool.runs)
    assertTrue(session.inputs[1].toolResults.single().content.contains("\"c1\""))
  }

  @Test
  fun textWithShowEndsTheTurnAndOwesResultsToTheNextRequest() = runTest {
    val tool = FakeTool("find_contact", item = ResultItem(HandleType.CONTACT, "7", mapOf("name" to "Harold"), RenderCard.Contact("Harold", null, null)))
    val session =
      ScriptedSession(
        msg("", "find_contact" to emptyMap()),
        msg("Which one?", "show" to mapOf("items" to listOf("c1")), "wait_for_reply" to emptyMap()),
        msg("Okay."),
      )
    val turnLoop = loop(session, tool)
    val host = FakeHost()
    val first = turnLoop.runTurn(host, user)
    assertEquals(TurnLoop.Outcome.Ended(waitForReply = true), first)
    assertEquals(1, host.shown.size)
    turnLoop.runTurn(host, user)
    // Results for show and wait_for_reply precede the next user message.
    assertEquals(listOf("show", "wait_for_reply"), session.inputs[2].toolResults.map { it.name })
  }

  @Test
  fun silentShowGetsAnotherRunThatIsToldToSpeak() = runTest {
    val tool = FakeTool("find_contact", item = ResultItem(HandleType.CONTACT, "7", mapOf("name" to "Harold"), RenderCard.Contact("Harold", null, null)))
    val session = ScriptedSession(msg("", "find_contact" to emptyMap()), msg("", "show" to mapOf("items" to listOf("c1"))), msg("Here he is."))
    loop(session, tool).runTurn(FakeHost(), user)
    assertEquals(3, session.inputs.size)
    assertTrue(session.inputs[2].toolResults.single().content.contains("can't hear anything yet"))
  }

  @Test
  fun idsFromACallInTheSameMessageAreRejectedBeforeApproval() = runTest {
    val find = FakeTool("find_contact", item = ResultItem(HandleType.CONTACT, "7", mapOf("name" to "Harold"), RenderCard.Contact("Harold", null, null)))
    val send =
      FakeTool(
        "send_message",
        level = ApprovalLevel.LOW,
        params = listOf(ToolParam("contact", ParamKind.HANDLE, "Contact id.", handleType = HandleType.CONTACT)),
      )
    val session =
      ScriptedSession(
        // Guesses c1 before seeing find_contact's result.
        msg("", "find_contact" to emptyMap(), "send_message" to mapOf("contact" to "c1")),
        msg("", "send_message" to mapOf("contact" to "c1")),
        msg("Sent."),
      )
    val host = FakeHost()
    loop(session, find, send).runTurn(host, user)
    assertEquals(1, host.approvals)
    assertEquals(1, send.runs)
    val rejected = session.inputs[1].toolResults.single { it.name == "send_message" }.content
    assertTrue(rejected.contains("same message"))
  }

  @Test
  fun emptyMessagesAreRetriedThenGivenUp() = runTest {
    val session = ScriptedSession(msg(""), msg(""), msg(""))
    val host = FakeHost()
    loop(session).runTurn(host, user)
    assertEquals(listOf(GIVE_UP_PHRASE), host.spoken)
  }

  @Test
  fun declinedApprovalSkipsTheToolAndPassesTheReplyOn() = runTest {
    val tool = FakeTool("send_message", level = ApprovalLevel.LOW)
    val audio = AudioSegment(ByteArray(3200))
    val host = FakeHost(ApprovalDecision.Declined(audio, "no say twenty minutes"))
    val session = ScriptedSession(msg("", "send_message" to emptyMap()), msg("Okay, I won't."))
    loop(session, tool).runTurn(host, user)
    assertEquals(0, tool.runs)
    assertEquals(1, host.approvals)
    val next = session.inputs[1]
    assertTrue(next.toolResults.single().content.contains("declined"))
    assertEquals(audio, next.user?.audio)
  }

  @Test
  fun textOnlyModelsGetTheDeclineTranscriptInTheResult() = runTest {
    val tool = FakeTool("send_message", level = ApprovalLevel.LOW)
    val host = FakeHost(ApprovalDecision.Declined(AudioSegment(ByteArray(10)), "no wait"))
    val session = ScriptedSession(msg("", "send_message" to emptyMap()), msg("Okay."))
    loop(session, tool, kind = ModelKind.TEXT_ONLY).runTurn(host, user)
    assertTrue(session.inputs[1].toolResults.single().content.contains("no wait"))
    assertEquals(null, session.inputs[1].user)
  }

  @Test
  fun unknownToolsGetAnErrorResult() = runTest {
    val session = ScriptedSession(msg("", "launch_rockets" to emptyMap()), msg("I can't do that."))
    loop(session).runTurn(FakeHost(), user)
    assertTrue(session.inputs[1].toolResults.single().content.contains("no tool named"))
  }

  @Test
  fun noApprovalNeededRunsDirectly() = runTest {
    val tool = FakeTool("set_timer")
    val host = FakeHost()
    loop(ScriptedSession(msg("", "set_timer" to emptyMap()), msg("Done.")), tool).runTurn(host, user)
    assertEquals(1, tool.runs)
    assertFalse(host.approvals > 0)
  }
}
