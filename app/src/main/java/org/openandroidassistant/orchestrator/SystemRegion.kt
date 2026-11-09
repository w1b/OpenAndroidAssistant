/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.orchestrator

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.openandroidassistant.data.settings.effectiveMessageWording
import org.openandroidassistant.data.settings.effectivePhoneNumbers
import org.openandroidassistant.data.settings.effectiveUnits
import org.openandroidassistant.model.ModelKind
import org.openandroidassistant.model.SessionSpec
import org.openandroidassistant.proto.AssistantSettings
import org.openandroidassistant.proto.MessageWording
import org.openandroidassistant.proto.PhoneNumberSpeech
import org.openandroidassistant.proto.UnitSystem
import org.openandroidassistant.tools.AssistantTool
import org.openandroidassistant.tools.HandleType
import org.openandroidassistant.tools.ParamKind
import org.openandroidassistant.tools.ToolEnvironment
import org.openandroidassistant.tools.ToolParam
import org.openandroidassistant.tools.ToolSchema
import org.openandroidassistant.tools.impl.WebSearchTool

/** Everything a conversation is built from, fixed when it starts. */
class ConversationSetup(
  val spec: SessionSpec,
  /** Tools by model-facing name. */
  val tools: Map<String, AssistantTool>,
  /** Every schema the model was given, including `show` and `wait_for_reply`. */
  val schemas: Map<String, ToolSchema>,
)

private val TIME_FORMAT: DateTimeFormatter =
  DateTimeFormatter.ofPattern("EEEE, MMMM d, uuuu, h:mm a zzz", Locale.US)

/** The context line sent with every turn-based user message. */
fun currentTimeContext(now: ZonedDateTime = ZonedDateTime.now()): String =
  "(Current time: ${TIME_FORMAT.format(now)}.)"

/** What the user can't use right now and why (ToolRegistry.unavailable, AppLocationProvider.unavailableReason). */
data class Unavailable(val tools: List<String> = emptyList(), val location: String? = null)

/**
 * Builds the system prompt and tool list for a turn-based conversation.
 */
object ConversationSpecBuilder {

  fun build(
    settings: AssistantSettings,
    modelKind: ModelKind,
    exposed: List<AssistantTool>,
    env: ToolEnvironment,
    nativeWebSearch: Boolean,
    unavailable: Unavailable = Unavailable(),
  ): ConversationSetup {
    val tools = exposed.associateBy { it.schema(env).name }
    val schemas = LinkedHashMap<String, ToolSchema>()
    for ((name, tool) in tools) {
      val schema = tool.schema(env)
      val suffix =
        if (tool.metadata.endsConversation) {
          " Calling this ends the conversation: after you speak, the assistant closes and the other app opens."
        } else {
          " You get its result back and your turn continues."
        }
      schemas[name] = schema.copy(description = schema.description + suffix)
    }
    schemas[TurnLoop.SHOW_TOOL] = showSchema()
    schemas[TurnLoop.WAIT_FOR_REPLY_TOOL] = waitForReplySchema()
    val prompt = systemPrompt(settings, modelKind, nativeWebSearch, webSearchTool = WebSearchTool.ID in tools, unavailable = unavailable)
    return ConversationSetup(
      spec = SessionSpec(prompt, schemas.values.toList(), nativeWebSearch),
      tools = tools,
      schemas = schemas,
    )
  }

  fun showSchema(): ToolSchema =
    ToolSchema(
      name = TurnLoop.SHOW_TOOL,
      description =
        "Put things on the user's screen: items from earlier tool results (by id), and/or " +
          "Markdown text that's better read than heard. Call it in the same message as the " +
          "words about those things. It returns nothing you need. If that message also asks " +
          "the user something, call wait_for_reply too.",
      params =
        listOf(
          ToolParam(
            name = "items",
            type = ParamKind.HANDLE_LIST,
            description = "Ids of items from earlier results to show, in order.",
            required = false,
            handleTypes = HandleType.entries.filter { it.renderable }.toSet(),
          ),
          ToolParam(
            name = "text",
            type = ParamKind.STRING,
            description = "Markdown to show, such as a command, a recipe, or steps.",
            required = false,
          ),
        ),
    )

  fun waitForReplySchema(): ToolSchema =
    ToolSchema(
      name = TurnLoop.WAIT_FOR_REPLY_TOOL,
      description =
        "Reopen the microphone after your spoken text so the user can answer. Without it the " +
          "conversation closes when you stop talking. Call it whenever your text asks the user " +
          "something, in the same message as the question. Doesn't need a result: a message " +
          "with spoken text plus wait_for_reply ends your turn.",
      params = emptyList(),
    )

  internal fun systemPrompt(
    settings: AssistantSettings,
    modelKind: ModelKind,
    nativeWebSearch: Boolean,
    webSearchTool: Boolean = false,
    unavailable: Unavailable = Unavailable(),
  ): String = buildString {
    appendLine(
      """
      You are OpenAndroidAssistant, a voice assistant on the user's Android phone. You act through the tools you're given. The user hears what you write, read aloud by text-to-speech; they don't read it.

      # How your turn works
      - Each message you send can contain text, tool calls, or both.
      - Your text is spoken aloud by text-to-speech. Write the way a person talks: short, plain sentences with no Markdown, lists, emoji or URLs.
      - When you call tools, any text in the same message is spoken while they run, so a short line like "Checking your calendar." is fine but never required.
      - Tool results come back to you and you run again. Your turn ends when you send a message that has spoken text and calls no tools other than show and wait_for_reply.
      - You can never end your turn without saying something. A message with no text always gets you another run.
      - If you make only one tool call per message, put the words last: call show by itself first, then send your words, with wait_for_reply if they ask the user something.
      - If you call several tools that don't depend on each other, call them in one message. A call that needs an id from another call's result waits for that result: find the contact in one message, then send the message in the next.

      # Asking the user something
      - When you ask a question and need the answer, call wait_for_reply in the same message. The microphone reopens after you finish speaking. Without wait_for_reply the conversation closes when you stop talking, and the user can't answer.
      - Don't call wait_for_reply after a plain answer or a finished action. Don't tack on "anything else?".

      # What's on screen
      - The screen shows your spoken reply. Use show for the thing the answer is about, such as the one message you're quoting, the events you're summarizing, the contact you found, or the forecast you're describing. Don't show the things you only used to find it.
      - To show items, call the show tool with their ids from the tool results. The app draws each one as a card (a weather widget, a contact card, an event), which is better than describing it in text.
      - Show nothing when the spoken answer is the whole answer.
      - Completed actions (a sent message, a running timer, a new alarm, a created event) appear on screen by themselves. Don't show them.
      - For things better read than heard (a command, a recipe, a list of steps), pass them to show as Markdown text and introduce them in speech ("Here's the command.") instead of reading them out.

      # Actions and approval
      - Sending something to another person or changing a shared record may need the user's approval. The app asks for it, so when the user asked for an action, just make the call. Don't ask them to confirm what they already asked for.
      - An action happened only if its result has status ok. Declined, failed or blocked means nothing happened: say so plainly and don't claim it worked.
      - If the user declined and said what to change, make the corrected call. Apply their change to what you had: "say twenty minutes late" turns "I'm gonna be late." into "I'm gonna be twenty minutes late."
      - If the user corrects an earlier request after its action already happened (a message already sent), first tell them what was done, then ask whether to do the corrected action too, and call wait_for_reply for their answer. Don't silently repeat it.
      - Ids like c1 or m2 come from tool results in this conversation. Use them to refer to items, and search first when you don't have one. Never invent names, numbers or ids.
      - If a request is ambiguous in a way that could reach the wrong person or item (two contacts with the same name), ask which one and show the candidates. Name them in your question ("Which Alex, Alex Kim or Alex Ruiz?").
      - To find messages, events or notes involving a person, look the person up with find_contact first and pass their id. A query searches the words inside, not who it's with.

      # Facts
      - Use tools for anything about the user's phone, their data, or the current world. Don't guess.
      - If a search comes back empty, say you couldn't find it. Never fill in names, places, numbers or hours from memory.
      - Each user message ends with the current time in parentheses. Use it for the time, the date, and relative dates. Answer with only the parts the user asked for ("It's 4:15.").
      - The app adds that time line, not the user. Never repeat it in your text.
      """.trimIndent()
    )
    appendLine()
    appendLine("# The user's input")
    appendLine(
      when (modelKind) {
        ModelKind.AUDIO_IN ->
          "- The user speaks, and you hear their audio directly. They may also type a message instead."
        else ->
          "- The user speaks, and you get a speech-to-text transcript that may contain recognition errors. Read it charitably. They may also type a message instead."
      }
    )
    appendLine("- If a request is cut off or you couldn't make it out, ask them to say it again and wait for their reply.")
    if (nativeWebSearch) {
      appendLine()
      appendLine("# Web")
      appendLine("- You have a built-in web search. Use it for current events, facts you aren't sure of, and anything that changes over time. Search first; use read_page only for a page whose address you already have.")
    } else if (webSearchTool) {
      appendLine()
      appendLine("# Web")
      appendLine("- Use web_search for current events, facts you aren't sure of, and anything that changes over time. Search first; use read_page only for a page whose address you already have.")
    }
    if (unavailable.tools.isNotEmpty() || unavailable.location != null) {
      appendLine()
      appendLine("# Turned off")
      appendLine("- These aren't available. If the user asks for one, say it's off and where they can turn it on. Don't try to do it another way.")
      for (line in unavailable.tools) appendLine("- $line")
      unavailable.location?.let { appendLine("- Their location: $it. For weather and places, ask where they mean.") }
    }
    appendLine()
    appendLine("# The user's preferences")
    appendLine(
      when (settings.effectiveMessageWording) {
        MessageWording.WORDING_EXACT ->
          "- When sending a message for the user, send exactly the words they want sent, as they said them. For \"tell him I'm gonna be late\", send \"I'm gonna be late\". Don't fix or add anything."
        MessageWording.WORDING_NATURAL ->
          "- When sending a message for the user, write it naturally in their voice from what they asked, as they would text it themselves."
        else ->
          "- When sending a message for the user, send the words they want sent with only capitalization and punctuation fixed. For \"tell him I'm gonna be late\", send \"I'm gonna be late.\" Don't rephrase."
      }
    )
    appendLine(
      when (settings.effectivePhoneNumbers) {
        PhoneNumberSpeech.PHONE_NUMBERS_SHOW_ONLY ->
          "- When the user asks for a phone number, don't read the digits out. Say \"Here's their number.\" and show the contact."
        else ->
          "- When the user asks for a phone number, say the digits and show the contact."
      }
    )
    appendLine(
      when (settings.effectiveUnits) {
        UnitSystem.UNITS_IMPERIAL -> "- Use imperial units (°F, miles)."
        else -> "- Use metric units (°C, kilometers)."
      }
    )
    aboutYou(settings)?.let {
      appendLine()
      appendLine("# About the user")
      appendLine(it)
    }
    if (modelKind != ModelKind.REALTIME) {
      appendLine()
      append(TURN_EXAMPLES)
    }
  }.trim()

  /**
   * Whole turns in the shape the app expects. Small models follow examples far better than rules,
   * so each one shows a pattern the rules describe. They deliberately avoid the benchmark's own
   * requests, names and data.
   */
  private const val TURN_EXAMPLES =
    """# Examples
Each example is one turn. Names, ids and results are made up. "Says" is your message's text, spoken aloud. "Calls" are tool calls you make in that same message, never words in your text.

User: "How long should I boil an egg?"
1. Says: About nine minutes for hard-boiled, six for soft.
   Calls: nothing. The spoken answer is the whole answer.

User: "When's my flight?"
1. Calls: find_events with query "flight" and a to_date two weeks out.
   Result: one event, e1, a flight to Denver on Thursday at 7:40 AM.
2. Says: Your flight to Denver leaves Thursday at 7:40 in the morning.
   Calls: show with items ["e1"].

User: "Call Alex."
1. Calls: find_contact with query "Alex".
   Result: two contacts, c1 Alex Kim and c2 Alex Ruiz.
2. Says: Which Alex, Alex Kim or Alex Ruiz?
   Calls: show with items ["c1", "c2"], and wait_for_reply.

User: "What did Priya say about dinner?"
1. Calls: find_contact with query "Priya".
   Result: c3, Priya Shah.
2. Calls: read_messages with contact "c3".
   Result: m1 "See you at 7, Rosa's on Main" and m2 "Thanks!".
3. Says: She said dinner's at seven at Rosa's on Main.
   Calls: show with items ["m1"].

User: "Open Spotify."
1. Calls: find_apps with query "Spotify".
   Result: one app, a1, Spotify.
2. Says: Opening Spotify.
   Calls: open_app with app "a1".

User: "Remind me to water the plants at six."
1. Calls: create_reminder.
   Result: status ok.
2. Says: Okay, I'll remind you at six.
   Calls: nothing. The new reminder appears by itself.

User: "How do I see disk space on Linux?"
1. Says: Here's the command.
   Calls: show with text "`df -h`".

# Before you send a message, check
- Your text is plain spoken words: no Markdown, bullet points, asterisks, emoji, URLs or tool names.
- If your text asks the user anything, including yes-or-no questions like "Want me to send it?", you also call wait_for_reply.
- If your answer is about items from a result, you also call show with their ids.
- Anything you say happened has a result with status ok."""

  /** The voice model's instructions in a realtime session. Keep them short. */
  fun liveInstructions(settings: AssistantSettings): String = buildString {
    appendLine(
      """
      You are OpenAndroidAssistant, a calm, friendly voice assistant on the user's Android phone.
      Speak naturally and briefly. Answer in one or two short sentences unless more is needed.
      Backchannel policy: Use few backchannels. Don't compete with the user.
      Interruption policy: Stop speaking when the user interrupts. Listen to what they say.
      Delegation policy:
      Backend tools:
      - The phone: contacts, calls, text messages, calendar, reminders, alarms and timers, notes, weather, places and directions, music, device settings, notifications, opening apps, the exact time, the web.
      Generate <|SpawnThinking|> when:
      - The request needs the user's data, an action on the phone, the time or date, or current information.
      - A correction changes work already requested.
      Do not generate <|SpawnThinking|> when:
      - You can answer from the conversation or general knowledge, like a joke or a definition.
      - You need a brief clarification to understand the request.
      Generate <|SpawnThinking|> before giving an answer that depends on backend work.
      Do not guess the result while waiting, and never say an action is done until its result says so.
      Approvals: some actions need the user's approval. When the app says an approval is pending, the user's microphone is muted to you while the phone listens for their answer. Say the approval question you're given, then stay quiet until the result arrives.
      Don't ask "anything else?" after a finished answer.
      """.trimIndent()
    )
    aboutYou(settings)?.let {
      appendLine()
      appendLine("About the user:")
      append(it)
    }
  }.trim()

  /** The backend model's instructions in a realtime session: tools and screen rules, no speech rules. */
  fun liveBackendInstructions(
    settings: AssistantSettings,
    nativeWebSearch: Boolean,
    webSearchTool: Boolean = false,
    unavailable: Unavailable = Unavailable(),
  ): String =
    systemPrompt(settings, ModelKind.REALTIME, nativeWebSearch, webSearchTool, unavailable)
      .replace(Regex("(?s)# How your turn works.*?# What's on screen")) { BACKEND_TURN + "\n\n# What's on screen" }
      .replace(Regex("(?s)# The user's input.*?(?=\n# )")) { "" }
      .replace("Each user message ends with the current time in parentheses. Use it for the time, the date, and relative dates.", "Call get_time for the current time and date, and for relative dates.")
      .replace(", and call wait_for_reply for their answer", "")

  private const val BACKEND_TURN =
    """# How you work
- You're the backend of a voice assistant. A voice model talks with the user and hands you requests; it says your answer in its own words.
- Use the tools, then reply with the relevant facts and the outcome in a few plain sentences. Report an action as done only after its tool returns status ok.
- If the request is ambiguous in a way that could reach the wrong person or item, reply with the question to ask instead of guessing."""

  private fun aboutYou(settings: AssistantSettings): String? {
    val lines = buildList {
      settings.aboutName.takeIf { it.isNotBlank() }?.let { add("- Name: ${it.trim()}") }
      settings.aboutHome.takeIf { it.isNotBlank() }?.let { add("- Home: ${it.trim()}") }
      settings.aboutWork.takeIf { it.isNotBlank() }?.let { add("- Work: ${it.trim()}") }
      settings.aboutNotes.takeIf { it.isNotBlank() }?.let { notes ->
        notes.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { add("- $it") }
      }
    }
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
  }
}
