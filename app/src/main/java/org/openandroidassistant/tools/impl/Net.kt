/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools.impl

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.openandroidassistant.model.sharedHttpClient

/** Identifies the app to public services (Nominatim's usage policy requires one). */
const val USER_AGENT = "OpenAndroidAssistant/0.1 (+https://github.com/openandroidassistant)"

class HttpStatusException(val code: Int, message: String) : IOException(message)

/** The client tools' requests go through; the scenario bench answers them with fake services. */
@androidx.annotation.VisibleForTesting
var toolHttpClient: OkHttpClient? = null

private val client: OkHttpClient
  get() = toolHttpClient ?: sharedHttpClient

/** GETs [url] and parses JSON. Throws on network errors and non-2xx answers. */
suspend fun httpGetJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
  JsonParser.parseString(httpGetText(url, headers))

/** GETs [url] as text. Without [followRedirects], a redirect throws like any other non-2xx answer. */
suspend fun httpGetText(url: String, headers: Map<String, String> = emptyMap(), followRedirects: Boolean = true): String =
  withContext(Dispatchers.IO) {
    val request =
      Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .apply { headers.forEach { (k, v) -> header(k, v) } }
        .build()
    (if (followRedirects) client else client.newBuilder().followRedirects(false).build()).newCall(request).execute().use { response ->
      val body = response.body.string()
      if (!response.isSuccessful) throw HttpStatusException(response.code, "HTTP ${response.code}: ${body.take(200)}")
      body
    }
  }

suspend fun httpPostJson(url: String, json: String, headers: Map<String, String> = emptyMap()): JsonElement =
  withContext(Dispatchers.IO) {
    val request =
      Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .apply { headers.forEach { (k, v) -> header(k, v) } }
        .post(json.toRequestBody("application/json".toMediaType()))
        .build()
    client.newCall(request).execute().use { response ->
      val body = response.body.string()
      if (!response.isSuccessful) throw HttpStatusException(response.code, "HTTP ${response.code}: ${body.take(200)}")
      JsonParser.parseString(body)
    }
  }
