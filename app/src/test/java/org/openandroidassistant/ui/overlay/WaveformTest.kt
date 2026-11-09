/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformTest {

  @Test
  fun fillsThenKeepsNewestSamplesAtRightEdge() {
    val samples = mutableListOf<Float>()

    samples.appendRolling(0.1f, capacity = 3)
    samples.appendRolling(0.2f, capacity = 3)
    samples.appendRolling(0.3f, capacity = 3)
    assertEquals(listOf(0.1f, 0.2f, 0.3f), samples)

    samples.appendRolling(0.4f, capacity = 3)
    assertEquals(listOf(0.2f, 0.3f, 0.4f), samples)
  }

  @Test
  fun clampsSamplesToDisplayRange() {
    val samples = mutableListOf<Float>()

    samples.appendRolling(-1f, capacity = 2)
    samples.appendRolling(2f, capacity = 2)

    assertEquals(listOf(0f, 1f), samples)
  }

  @Test
  fun rawSpeechRmsUsesPerceptualDisplayRange() {
    assertEquals(0f, waveformDisplayLevel(0f))
    assertTrue(waveformDisplayLevel(0.01f) > 0.25f)
    assertTrue(waveformDisplayLevel(0.05f) > 0.7f)
    assertTrue(waveformDisplayLevel(0.1f) > 0.9f)
    assertEquals(1f, waveformDisplayLevel(1f))
  }
}
