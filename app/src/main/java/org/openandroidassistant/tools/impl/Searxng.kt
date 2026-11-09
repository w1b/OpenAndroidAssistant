/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools.impl

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/** How a SearXNG search went. */
sealed interface SearxngReply {
  data class Results(val json: JsonObject) : SearxngReply

  /** Any non-2xx answer. A redirect counts: it's usually to a login page, and it would carry the login headers elsewhere. */
  data class Refused(val code: Int) : SearxngReply

  /** A 2xx that isn't search results: usually a login page in front of the instance. */
  data object NotResults : SearxngReply

  data class Unreachable(val message: String) : SearxngReply
}

/** Searches the SearXNG instance at [base] with [headers] (its login). Used by web_search and the settings check. */
suspend fun searxngSearch(base: String, query: String, headers: Map<String, String>): SearxngReply {
  val url = "$base/search?format=json&q=${URLEncoder.encode(query, "UTF-8")}&language=${Locale.getDefault().toLanguageTag()}"
  val text =
    try {
      httpGetText(url, headers, followRedirects = false)
    } catch (e: HttpStatusException) {
      return SearxngReply.Refused(e.code)
    } catch (e: IOException) {
      return SearxngReply.Unreachable(e.message ?: e::class.simpleName.orEmpty())
    }
  val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return SearxngReply.NotResults
  return SearxngReply.Results(json)
}
