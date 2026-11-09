/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.data.settings

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A request header the user added for their SearXNG instance, for a proxy in front of it. */
data class ExtraHeader(val name: String, val value: String)

/** The headers, stored as one secret: a JSON array of [name, value] pairs. */
fun decodeHeaders(stored: String?): List<ExtraHeader> {
  if (stored.isNullOrBlank()) return emptyList()
  return runCatching { JsonParser.parseString(stored).asJsonArray.map { it.asJsonArray.let { p -> ExtraHeader(p[0].asString, p[1].asString) } } }
    .getOrDefault(emptyList())
}

fun encodeHeaders(headers: List<ExtraHeader>): String? =
  if (headers.isEmpty()) null
  else JsonArray().apply { headers.forEach { h -> add(JsonArray().apply { add(h.name); add(h.value) }) } }.toString()

/** Why a header can't be sent, or null when it can. The same rules OkHttp enforces. */
fun headerProblem(name: String, value: String): String? =
  when {
    name.isEmpty() -> "Enter a name."
    name.any { it !in '!'..'~' || it == ':' } -> "No spaces or colons in the name."
    value.any { it != '\t' && it !in ' '..'~' } -> "Only plain ASCII characters in the value."
    else -> null
  }

/** An instance address with any login written into it ("https://user:pass@host") taken out. */
data class InstanceAddress(val url: String, val username: String? = null, val password: String? = null)

fun splitLogin(input: String): InstanceAddress {
  val trimmed = input.trim().trimEnd('/')
  val parsed = trimmed.toHttpUrlOrNull()
  if (parsed == null || parsed.username.isEmpty()) return InstanceAddress(trimmed)
  return InstanceAddress(parsed.newBuilder().username("").password("").build().toString().trimEnd('/'), parsed.username, parsed.password.ifEmpty { null })
}

/** Headers for every request to the SearXNG instance: basic auth, then the user's own (which win). */
fun SecretStore.searxngHeaders(): Map<String, String> {
  val username = get(Secret.SEARXNG_USERNAME)
  val password = get(Secret.SEARXNG_PASSWORD).orEmpty()
  val extra = decodeHeaders(get(Secret.SEARXNG_HEADERS))
  return buildMap {
    username?.let { put("Authorization", Credentials.basic(it, password, Charsets.UTF_8)) }
    extra.forEach { put(it.name, it.value) }
  }
}
