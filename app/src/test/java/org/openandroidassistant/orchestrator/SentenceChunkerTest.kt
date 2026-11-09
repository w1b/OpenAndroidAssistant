/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceChunkerTest {
  @Test
  fun takesCompleteSentencesAsTheyStream() {
    val c = SentenceChunker()
    assertEquals(emptyList<Pair<Int, String>>(), c.take("It's 11 degrees and cloud"))
    val first = c.take("It's 11 degrees and cloudy in London. With rain")
    assertEquals(listOf(0 to "It's 11 degrees and cloudy in London."), first)
    val full = "It's 11 degrees and cloudy in London. With rain after three."
    assertEquals(emptyList<Pair<Int, String>>(), c.take(full))
    assertEquals(37 to " With rain after three.", c.rest(full))
    assertNull(c.rest(full))
  }

  @Test
  fun shortFragmentsJoinTheNextSentence() {
    val c = SentenceChunker()
    val text = "Dr. Smith is in at nine tomorrow. "
    assertEquals(listOf(0 to "Dr. Smith is in at nine tomorrow."), c.take(text))
  }

  @Test
  fun decimalsDontSplit() {
    val c = SentenceChunker()
    assertEquals(emptyList<Pair<Int, String>>(), c.take("It costs 3.50 dollars"))
  }
}
