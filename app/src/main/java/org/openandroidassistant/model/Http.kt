/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.model

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Shared HTTP client. Read timeout is generous: reasoning models can think a while. A redirect
 * from HTTPS to HTTP isn't followed.
 */
val sharedHttpClient: OkHttpClient by lazy {
  OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .followSslRedirects(false)
    .build()
}

private val JSON_MEDIA = "application/json".toMediaType()

internal sealed interface StreamOutcome {
  data object Done : StreamOutcome

  data object Cancelled : StreamOutcome

  data class Failed(val error: ModelError) : StreamOutcome
}

/**
 * POSTs [body] and feeds each server-sent `data:` payload to [onEvent] until the stream ends.
 * [onEvent] returns false to stop early. [callHolder] receives the call so another thread can
 * cancel it.
 */
internal suspend fun postServerSentEvents(
  client: OkHttpClient,
  url: String,
  headers: Map<String, String>,
  body: JsonObject,
  callHolder: (Call) -> Unit,
  onEvent: (String) -> Boolean,
): StreamOutcome =
  withContext(Dispatchers.IO) {
    val request =
      Request.Builder()
        .url(url)
        .apply { headers.forEach { (k, v) -> header(k, v) } }
        .header("Accept", "text/event-stream")
        .post(body.toString().toRequestBody(JSON_MEDIA))
        .build()
    val call = client.newCall(request)
    callHolder(call)
    suspendCancellableCoroutine { continuation ->
      continuation.invokeOnCancellation { call.cancel() }
      val outcome =
        try {
          call.execute().use { response ->
            if (!response.isSuccessful) {
              val text = response.body.string()
              StreamOutcome.Failed(
                ModelError.Failed("HTTP ${response.code}: ${errorMessage(text) ?: response.message}")
              )
            } else {
              val source = response.body.source()
              var stopped = false
              while (!stopped) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isNotEmpty() && !onEvent(data)) stopped = true
              }
              StreamOutcome.Done
            }
          }
        } catch (e: IOException) {
          when {
            call.isCanceled() -> StreamOutcome.Cancelled
            e.isUnreachable() -> StreamOutcome.Failed(ModelError.Unreachable(e.describe()))
            else -> StreamOutcome.Failed(ModelError.Failed(e.describe()))
          }
        }
      continuation.resume(outcome)
    }
  }

/** True when the request never reached the provider. */
internal fun IOException.isUnreachable(): Boolean =
  this is UnknownHostException ||
    this is ConnectException ||
    this is NoRouteToHostException ||
    this is SSLException ||
    (this is InterruptedIOException && message?.contains("connect", ignoreCase = true) == true)

private fun IOException.describe(): String = "${this::class.simpleName}: ${message.orEmpty()}"

/** Pulls `error.message` out of an OpenAI-style error body. */
internal fun errorMessage(body: String): String? =
  runCatching {
      val error = json.parseToJsonElement(body).jsonObject["error"]
      (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
        ?: error?.jsonPrimitive?.contentOrNull
    }
    .getOrNull()
