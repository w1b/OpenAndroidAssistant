/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.runtime

/** Local model lifecycle. Loading takes tens of seconds, so the cold path is normal UX. */
sealed interface LoadState {
  data object Unloaded : LoadState

  data object Loading : LoadState

  data object Ready : LoadState

  data class Failed(val message: String) : LoadState
}

/** 16 kHz mono 16-bit little-endian PCM. */
data class AudioSegment(val pcm: ByteArray, val sampleRateHz: Int = 16_000) {
  val durationMs: Long
    get() = pcm.size * 1000L / (sampleRateHz * 2)

  override fun equals(other: Any?): Boolean =
    other is AudioSegment && other.sampleRateHz == sampleRateHz && other.pcm.contentEquals(pcm)

  override fun hashCode(): Int = 31 * pcm.contentHashCode() + sampleRateHz

  companion object {
    fun fromShorts(samples: ShortArray, sampleRateHz: Int = 16_000): AudioSegment {
      val bytes = ByteArray(samples.size * 2)
      for (i in samples.indices) {
        val v = samples[i].toInt()
        bytes[2 * i] = (v and 0xFF).toByte()
        bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
      }
      return AudioSegment(bytes, sampleRateHz)
    }
  }
}

/** Wraps the PCM in a WAV header. */
fun AudioSegment.toWavBytes(): ByteArray {
  val dataSize = pcm.size
  val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
  header.put("RIFF".toByteArray())
  header.putInt(36 + dataSize)
  header.put("WAVE".toByteArray())
  header.put("fmt ".toByteArray())
  header.putInt(16)
  header.putShort(1) // PCM
  header.putShort(1) // mono
  header.putInt(sampleRateHz)
  header.putInt(sampleRateHz * 2)
  header.putShort(2) // block align
  header.putShort(16) // bits per sample
  header.put("data".toByteArray())
  header.putInt(dataSize)
  return header.array() + pcm
}
