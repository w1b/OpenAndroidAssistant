/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.audio

/** Interleaved little-endian signed 16-bit PCM ready for controlled [android.media.AudioTrack]. */
data class Pcm16Audio(
  val bytes: ByteArray,
  val sampleRate: Int,
  val channels: Int = 1,
  val dataOffset: Int = 0,
)
