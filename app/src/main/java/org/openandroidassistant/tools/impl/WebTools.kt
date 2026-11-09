/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools.impl

import com.google.gson.JsonObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import org.openandroidassistant.data.settings.Secret
import org.openandroidassistant.data.settings.SecretStore
import org.openandroidassistant.data.settings.effectiveJinaUrl
import org.openandroidassistant.data.settings.effectiveReadPageProvider
import org.openandroidassistant.data.settings.searxngHeaders
import org.openandroidassistant.data.settings.webSearchMode
import org.openandroidassistant.orchestrator.RenderCard
import org.openandroidassistant.proto.ReadPageProvider
import org.openandroidassistant.proto.WebSearchMode
import org.openandroidassistant.tools.BaseTool
import org.openandroidassistant.tools.HandleType
import org.openandroidassistant.tools.ParamKind
import org.openandroidassistant.tools.ResultItem
import org.openandroidassistant.tools.ToolCategory
import org.openandroidassistant.tools.ToolContext
import org.openandroidassistant.tools.ToolMetadata
import org.openandroidassistant.tools.ToolOutcome
import org.openandroidassistant.tools.ToolParam
import org.openandroidassistant.tools.ToolSchema
import org.openandroidassistant.tools.ValidatedCall

/** Longest page text handed to the model. */
private const val PAGE_MAX_CHARS = 20_000

/** Web search through the user's SearXNG instance or Tavily key. Exposed only when configured. */
class WebSearchTool @Inject constructor(private val secrets: SecretStore) : BaseTool() {
  companion object {
    const val ID = "web_search"
  }

  override val schema =
    ToolSchema(
      name = "web_search",
      description = "Search the web. Returns titles, links and snippets; use read_page to read a result in full.",
      params = listOf(ToolParam("query", ParamKind.STRING, "What to search for.")),
    )

  override val metadata =
    ToolMetadata(
      id = "web_search",
      title = "Web search",
      summary = "Search the web with SearXNG or Tavily.",
      category = ToolCategory.INFORMATION,
      allowedWhenLockedDefault = true,
      progressPhrase = "Searching the web.",
      requiresConfiguration = true,
    )

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val query = call.stringArg("query") ?: return ToolOutcome.Steering("error", "What should I search for?")
    return if (context.settings.webSearchMode() == WebSearchMode.WEB_SEARCH_TAVILY) tavily(query) else searxng(query, context)
  }

  private suspend fun searxng(query: String, context: ToolContext): ToolOutcome {
    val base = context.settings.searxngUrl.trim().trimEnd('/')
    if (base.isEmpty()) return ToolOutcome.Steering("error", "No SearXNG instance is set up.")
    val json =
      when (val reply = searxngSearch(base, query, secrets.searxngHeaders())) {
        is SearxngReply.Results -> reply.json
        is SearxngReply.Refused ->
          return ToolOutcome.Steering(
            "error",
            when (reply.code) {
              401 -> "The SearXNG instance rejected the login. Tell the user to check the SearXNG login in the app's settings."
              403 -> "The SearXNG instance refused the search: either its json format is off or the login is wrong. Tell the user to check SearXNG in the app's settings."
              429 -> "The SearXNG instance's rate limiter blocked the search. Tell the user."
              in 300..399 -> "The SearXNG instance redirected instead of answering, probably to a login page. Tell the user to check the SearXNG login in the app's settings."
              else -> "Web search failed (HTTP ${reply.code}). Tell the user."
            },
          )
        SearxngReply.NotResults ->
          return ToolOutcome.Steering("error", "The SearXNG instance answered with a web page, not results, probably a login page. Tell the user to check the SearXNG login in the app's settings.")
        is SearxngReply.Unreachable -> return ToolOutcome.Steering("error", "Couldn't reach the SearXNG instance (${reply.message}). Tell the user.")
      }
    val answers = json.arr("answers")?.mapNotNull { it.takeIf { a -> a.isJsonPrimitive }?.asString }.orEmpty()
    val results = json.arr("results")?.take(8)?.mapNotNull { el -> el.asJsonObject.let { o -> o.str("url")?.let { webResult(it, o.str("title"), o.str("content"), o.str("publishedDate")) } } }.orEmpty()
    return outcome(results, answers.joinToString(" "))
  }

  private suspend fun tavily(query: String): ToolOutcome {
    val key = secrets.get(Secret.TAVILY_KEY) ?: return ToolOutcome.Steering("error", "No Tavily API key is set up. Tell the user to add one in the app's settings.")
    val body =
      JsonObject().apply {
        addProperty("query", query)
        // Basic costs 1 credit; advanced costs 2.
        addProperty("search_depth", "basic")
        addProperty("max_results", 8)
        addProperty("include_answer", "basic")
      }
    val json =
      try {
        httpPostJson("https://api.tavily.com/search", body.toString(), mapOf("Authorization" to "Bearer $key")).asJsonObject
      } catch (e: HttpStatusException) {
        return ToolOutcome.Steering(
          "error",
          when (e.code) {
            401 -> "The Tavily API key was rejected. Tell the user to check it in the app's settings."
            429, 432, 433 -> "Tavily's search limit has been reached. Tell the user."
            else -> "Web search failed (${e.message}). Tell the user."
          },
        )
      }
    val results = json.arr("results")?.mapNotNull { el -> el.asJsonObject.let { o -> o.str("url")?.let { webResult(it, o.str("title"), o.str("content"), o.str("published_date")) } } }.orEmpty()
    return outcome(results, json.str("answer").orEmpty())
  }

  private fun webResult(url: String, title: String?, snippet: String?, published: String?) =
    ResultItem(
      type = HandleType.WEB,
      nativeId = url,
      projection = mapOf("title" to (title ?: url), "url" to url, "snippet" to snippet, "published" to published),
      renderPayload = RenderCard.WebResult(title ?: url, url, snippet),
    )

  private fun outcome(items: List<ResultItem>, answer: String): ToolOutcome {
    if (items.isEmpty() && answer.isBlank()) return ToolOutcome.Steering("empty", "No results. Try different words.")
    return ToolOutcome.Success(items = items, extras = if (answer.isBlank()) null else JsonObject().apply { addProperty("answer", answer) })
  }
}

/** Read a web page as text: Jina Reader by default, or Firecrawl. */
class ReadPageTool @Inject constructor(private val secrets: SecretStore) : BaseTool() {
  override val schema =
    ToolSchema(
      name = "read_page",
      description = "Read a web page and get its main text as Markdown.",
      params = listOf(ToolParam("url", ParamKind.STRING, "The page's full URL.")),
    )

  override val metadata =
    ToolMetadata(
      id = "read_page",
      title = "Read web pages",
      summary = "Read a web page's text.",
      category = ToolCategory.INFORMATION,
      allowedWhenLockedDefault = true,
      progressPhrase = "Reading the page.",
    )

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome {
    val url = call.stringArg("url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
      ?: return ToolOutcome.Steering("error", "url must start with http:// or https://.")
    val text =
      when (context.settings.effectiveReadPageProvider) {
        ReadPageProvider.READ_PAGE_FIRECRAWL -> firecrawl(url, context)
        else ->
          httpGetText(
            "${context.settings.effectiveJinaUrl}/$url",
            mapOf("X-Return-Format" to "markdown") + secrets.get(Secret.JINA_KEY)?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty(),
          )
      }
    if (text.isBlank()) return ToolOutcome.Steering("empty", "The page had no readable text.")
    val clipped = if (text.length > PAGE_MAX_CHARS) text.take(PAGE_MAX_CHARS) + "\n\n[Page truncated]" else text
    return ToolOutcome.Success(items = emptyList(), text = clipped)
  }

  private suspend fun firecrawl(url: String, context: ToolContext): String {
    val base = context.settings.firecrawlUrl.trim().trimEnd('/').ifEmpty { "https://api.firecrawl.dev" }
    val key = secrets.get(Secret.FIRECRAWL_KEY)
    val body =
      JsonObject().apply {
        addProperty("url", url)
        add("formats", com.google.gson.JsonArray().apply { add("markdown") })
        addProperty("onlyMainContent", true)
      }
    val json = httpPostJson("$base/v2/scrape", body.toString(), key?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()).asJsonObject
    return json.obj("data")?.str("markdown").orEmpty()
  }
}

/** The exact current time, for the realtime backend (turn-based models get it with every message). */
class TimeTool @Inject constructor() : BaseTool() {
  override val schema = ToolSchema(name = "get_time", description = "Get the exact current local date and time.", params = emptyList())

  override val metadata =
    ToolMetadata(id = "get_time", title = "Clock", summary = "The current date and time.", category = ToolCategory.ESSENTIALS, allowedWhenLockedDefault = true)

  override suspend fun execute(call: ValidatedCall, context: ToolContext): ToolOutcome =
    ToolOutcome.Success(
      items = emptyList(),
      text = DateTimeFormatter.ofPattern("EEEE, MMMM d, uuuu, h:mm a zzz", Locale.US).format(ZonedDateTime.now()),
    )

  companion object {
    const val ID = "get_time"
  }
}
