/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.tools.impl

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearxngTest {
  private var seen: Request? = null

  private fun answer(code: Int, body: String, headers: Map<String, String> = emptyMap()) {
    toolHttpClient =
      OkHttpClient.Builder()
        .addInterceptor(
          Interceptor { chain ->
            seen = chain.request()
            Response.Builder()
              .request(chain.request())
              .protocol(Protocol.HTTP_1_1)
              .code(code)
              .message("")
              .apply { headers.forEach { (k, v) -> header(k, v) } }
              .body(body.toResponseBody("text/html".toMediaType()))
              .build()
          }
        )
        .build()
  }

  @After
  fun reset() {
    toolHttpClient = null
  }

  private fun search() = runBlocking { searxngSearch("https://s.example", "cats", mapOf("Authorization" to "Basic YWxpY2U6cHc=", "X-API-Key" to "k")) }

  @Test
  fun resultsComeBackWithTheLoginSent() {
    answer(200, """{"results":[],"answers":[]}""")
    assertTrue(search() is SearxngReply.Results)
    assertEquals("Basic YWxpY2U6cHc=", seen?.header("Authorization"))
    assertEquals("k", seen?.header("X-API-Key"))
    assertEquals("json", seen?.url?.queryParameter("format"))
  }

  @Test
  fun aRejectedLoginIsRefused() {
    answer(401, "Unauthorized")
    assertEquals(SearxngReply.Refused(401), search())
  }

  @Test
  fun aRedirectIsRefusedNotFollowed() {
    answer(302, "", mapOf("Location" to "https://auth.example/login"))
    assertEquals(SearxngReply.Refused(302), search())
  }

  @Test
  fun aLoginPageIsNotResults() {
    answer(200, "<!DOCTYPE html><html><body>Sign in</body></html>")
    assertEquals(SearxngReply.NotResults, search())
  }
}
