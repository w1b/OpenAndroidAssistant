/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechNormalizerTest {

  private val normalizer = SpeechNormalizer()

  @Test
  fun expandsCurrency() {
    assertEquals("that is 5 dollars", normalizer.normalize("that is \$5", "en-US"))
    assertEquals(
      "it costs 5 dollars and 20 cents",
      normalizer.normalize("it costs \$5.20", "en-US"),
    )
  }

  @Test
  fun expandsDegreesAndPercent() {
    assertTrue(normalizer.normalize("it's 72°", "en-US").contains("72 degrees"))
    assertTrue(normalizer.normalize("30% chance", "en-US").contains("30 percent"))
  }

  @Test
  fun expandsRanges() {
    assertEquals("5 to 10 minutes", normalizer.normalize("5-10 minutes", "en-US"))
  }

  @Test
  fun speaksClockTimesAsWords() {
    // KittenTTS read "3:00 PM" as numbers.
    assertEquals("at three P M", normalizer.normalize("at 3:00 PM", "en-US"))
    assertEquals("seven oh five A M", normalizer.normalize("7:05am", "en-US"))
    assertEquals("four thirty", normalizer.normalize("4:30", "en-US"))
    assertEquals("three P M", normalizer.normalize("15:00", "en-US"))
    assertEquals("twelve fifteen A M", normalizer.normalize("00:15", "en-US"))
    assertEquals("nine o'clock", normalizer.normalize("9:00", "en-US"))
    assertEquals("from eleven forty five A M to one P M", normalizer.normalize("from 11:45 a.m. to 1:00 p.m.", "en-US"))
    assertEquals("three P M to four P M, to your calendar?", normalizer.normalize("3:00 PM–4:00 PM, to your calendar?", "en-US"))
  }

  @Test
  fun stripsMarkdownAndUrls() {
    val out = normalizer.normalize("See **this** and https://example.com/x now", "en-US")
    assertFalse(out.contains("*"))
    assertFalse(out.contains("http"))
    assertTrue(out.contains("See"))
    assertTrue(out.contains("now"))
  }

  @Test
  fun stripsEmoji() {
    val out = normalizer.normalize("done ✅ 🎉", "en-US")
    assertFalse(out.contains("✅"))
    assertEquals("done", out.trim())
  }

  @Test
  fun unknownLocaleFallsBackToPlainPassThrough() {
    // Never apply English expansions to another language — just strip unspeakables.
    val out = normalizer.normalize("il fait 20°", "fr-FR")
    assertFalse(out.contains("degrees"))
    assertTrue(out.contains("20"))
  }
}
