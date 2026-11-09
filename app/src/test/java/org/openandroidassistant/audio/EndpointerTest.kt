/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointerTest {

  private fun endpointer(config: EndpointConfig) = Endpointer(config)

  private val speech = true
  private val silence = false

  @Test
  fun emitsSpeechStartedThenEndpointsOnSilence() {
    val ep = endpointer(EndpointConfig(silenceTimeoutMs = 200, noSpeechTimeoutMs = 5000))
    assertEquals(EndpointEvent.SpeechStarted, ep.onFrame(speech))
    // Feed silence until the trailing-silence timeout (200 ms = 10 frames of 20 ms).
    var endpoint: EndpointEvent? = null
    repeat(20) { if (endpoint == null) endpoint = ep.onFrame(silence) }
    assertTrue(endpoint is EndpointEvent.Endpoint)
    assertEquals(EndReason.SILENCE, (endpoint as EndpointEvent.Endpoint).reason)
  }

  @Test
  fun noSpeechTimeoutWhenSilentThroughout() {
    val ep = endpointer(EndpointConfig(noSpeechTimeoutMs = 100))
    var event: EndpointEvent? = null
    repeat(10) { if (event == null) event = ep.onFrame(silence) }
    assertEquals(EndpointEvent.NoSpeech, event)
  }

  @Test
  fun silenceResetsWhenSpeechResumes() {
    val ep = endpointer(EndpointConfig(silenceTimeoutMs = 200, noSpeechTimeoutMs = 5000))
    ep.onFrame(speech)
    repeat(5) { ep.onFrame(silence) } // 100 ms silence, below timeout
    ep.onFrame(speech) // resets
    // Now only short silence again — should not endpoint yet.
    assertNull(ep.onFrame(silence))
    assertNull(ep.onFrame(silence))
  }

  @Test
  fun maxLengthForcesEndpoint() {
    val ep =
      endpointer(EndpointConfig(silenceTimeoutMs = 100_000, maxTurnMs = 100, noSpeechTimeoutMs = 5000))
    ep.onFrame(speech)
    var event: EndpointEvent? = null
    repeat(10) { if (event == null || event is EndpointEvent.SpeechStarted) event = ep.onFrame(speech) }
    assertTrue(event is EndpointEvent.Endpoint)
    assertEquals(EndReason.MAX_LENGTH, (event as EndpointEvent.Endpoint).reason)
  }
}
