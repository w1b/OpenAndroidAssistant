/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.audio

import java.io.File
import java.io.InputStream

/**
 * Debug builds only: plays a 16 kHz mono PCM16 file into the mic pipeline in real time, then
 * silence, so emulator tests exercise endpointing, speech-to-text and voice approval end to end.
 */
class PacedPcmStream(file: File) : InputStream() {
  private val data = file.readBytes()
  private var position = 0
  private val startedAt = System.nanoTime()
  private var delivered = 0L

  override fun read(): Int {
    val one = ByteArray(1)
    return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
  }

  override fun read(b: ByteArray, off: Int, len: Int): Int {
    // Real time: 32 bytes per millisecond at 16 kHz mono PCM16.
    val dueNs = startedAt + (delivered + len) * 1_000_000L / 32
    val waitNs = dueNs - System.nanoTime()
    if (waitNs > 0) Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt())
    for (i in 0 until len) b[off + i] = if (position < data.size) data[position++] else 0
    delivered += len
    return len
  }
}
