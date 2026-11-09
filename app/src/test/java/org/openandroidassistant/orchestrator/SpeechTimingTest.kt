/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTimingTest {

  @Test
  fun `cues retain ranges in the original displayed text`() {
    val cues = proportionalSpeechCues("Hello, bright world!")

    assertEquals(
      listOf(
        SpeechTextRange(0, 6),
        SpeechTextRange(7, 13),
        SpeechTextRange(14, 20),
      ),
      cues.map { it.textRange },
    )
  }

  @Test
  fun `normalization expansion gives a visible token more playback time`() {
    val cues =
      proportionalSpeechCues("\$5 now") { token ->
        if (token == "\$5") "5 dollars" else token
      }

    val currencyShare = cues[0].endFraction - cues[0].startFraction
    val nowShare = cues[1].endFraction - cues[1].startFraction
    assertTrue(currencyShare > nowShare * 2)
    assertEquals(SpeechTextRange(0, 2), cues[0].textRange)
  }

  @Test
  fun `sentence punctuation keeps the reveal on the preceding word through a pause`() {
    val punctuated = proportionalSpeechCues("Hi. There")
    val plain = proportionalSpeechCues("Hi There")

    assertTrue(punctuated.first().endFraction > plain.first().endFraction)
  }

  @Test
  fun `reveal advances continuously through tokens and reaches the end`() {
    val cues = proportionalSpeechCues("one two three")
    val first = cues.first()
    val halfwayThroughFirst = (first.startFraction + first.endFraction) / 2f

    assertEquals(
      0f,
      speechRevealProgress(cues, 0f, displayTextLength = 13)!!,
      0.0001f,
    )
    assertEquals(
      1.5f / 13f,
      speechRevealProgress(cues, halfwayThroughFirst, displayTextLength = 13)!!,
      0.0001f,
    )
    assertEquals(
      4f / 13f,
      speechRevealProgress(cues, first.endFraction, displayTextLength = 13)!!,
      0.0001f,
    )
    assertEquals(1f, speechRevealProgress(cues, 1f, displayTextLength = 13)!!, 0f)
  }

  @Test
  fun `tokens removed by normalization receive no cue`() {
    val cues =
      proportionalSpeechCues("See https://example.com now") { token ->
        if (token.startsWith("http")) "" else token
      }

    assertEquals(
      listOf(SpeechTextRange(0, 3), SpeechTextRange(24, 27)),
      cues.map { it.textRange },
    )
  }
}
