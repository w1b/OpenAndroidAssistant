/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearxngLoginTest {
  @Test
  fun loginInAPastedAddressIsTakenOut() {
    assertEquals(InstanceAddress("https://search.example.org", "alice", "p@ss word"), splitLogin("https://alice:p%40ss%20word@search.example.org/"))
    assertEquals(InstanceAddress("https://example.org/searxng", "alice"), splitLogin("https://alice@example.org/searxng"))
    assertEquals(InstanceAddress("https://search.example.org"), splitLogin(" https://search.example.org/ "))
  }

  @Test
  fun headersSurviveStorage() {
    val headers = listOf(ExtraHeader("CF-Access-Client-Id", "abc.access"), ExtraHeader("X-API-Key", "s3cret \"quoted\""))
    assertEquals(headers, decodeHeaders(encodeHeaders(headers)))
    assertNull(encodeHeaders(emptyList()))
    assertEquals(emptyList<ExtraHeader>(), decodeHeaders(null))
    assertEquals(emptyList<ExtraHeader>(), decodeHeaders("not json"))
  }

  @Test
  fun onlySendableHeadersAreAccepted() {
    assertNull(headerProblem("X-API-Key", "abc123"))
    assertEquals("Enter a name.", headerProblem("", "abc"))
    assertEquals("No spaces or colons in the name.", headerProblem("X API Key", "abc"))
    assertEquals("No spaces or colons in the name.", headerProblem("X-Key:", "abc"))
    assertEquals("Only plain ASCII characters in the value.", headerProblem("X-API-Key", "clé"))
  }
}
