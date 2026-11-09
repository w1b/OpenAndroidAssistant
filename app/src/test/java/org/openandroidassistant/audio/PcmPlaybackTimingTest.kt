/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcmPlaybackTimingTest {

  @Test
  fun `audible window trims silence and retains edge padding`() {
    val samples = ShortArray(100)
    for (index in 30 until 70) samples[index] = 1_000

    val window =
      Pcm16Audio(bytes = samples.toLittleEndianBytes(), sampleRate = 1_000)
        .audibleFrameWindow(amplitudeThreshold = 256, edgePaddingMs = 10)

    assertEquals(AudibleFrameWindow(20, 80, 100), window)
    assertNull(window.progressAt(19))
    assertEquals(0f, window.progressAt(20))
    assertEquals(0.5f, window.progressAt(50))
    assertNull(window.progressAt(80))
  }

  @Test
  fun `silent audio uses its full buffer as a stable timeline`() {
    val window =
      Pcm16Audio(bytes = ShortArray(40).toLittleEndianBytes(), sampleRate = 1_000)
        .audibleFrameWindow()

    assertEquals(AudibleFrameWindow(0, 40, 40), window)
  }

  @Test
  fun `trim cuts a faint breath before speech but keeps a soft onset`() {
    // 1 kHz: 10 frames per 10 ms window. Breath at -45 dBFS, a dip, then speech.
    val samples = ShortArray(1_000)
    for (i in 20 until 150) samples[i] = if (i % 2 == 0) 180 else -180
    for (i in 300 until 800) samples[i] = if (i % 2 == 0) 8_000 else -8_000
    val trimmed = Pcm16Audio(samples.toLittleEndianBytes(), sampleRate = 1_000).trimmedForSpeech()
    assertEquals(510 * 2, trimmed.bytes.size) // from 290 (the dip) to 800

    // A soft "s" running straight into the vowel is kept.
    val soft = ShortArray(1_000)
    for (i in 200 until 300) soft[i] = if (i % 2 == 0) 400 else -400
    for (i in 300 until 800) soft[i] = if (i % 2 == 0) 8_000 else -8_000
    val kept = Pcm16Audio(soft.toLittleEndianBytes(), sampleRate = 1_000).trimmedForSpeech()
    assertEquals(610 * 2, kept.bytes.size) // from 190 to 800
  }

  private fun ShortArray.toLittleEndianBytes(): ByteArray {
    val bytes = ByteArray(size * 2)
    forEachIndexed { index, value ->
      val sample = value.toInt()
      bytes[index * 2] = (sample and 0xff).toByte()
      bytes[index * 2 + 1] = ((sample ushr 8) and 0xff).toByte()
    }
    return bytes
  }
}
