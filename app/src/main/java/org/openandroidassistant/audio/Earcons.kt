/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Short, learnable non-speech machine-state signals: mic-open, endpoint
 * acknowledgment, and a soft cancel. Generated procedurally — no assets, identical everywhere.
 * No persistent or looping earcons.
 */
@Singleton
class Earcons @Inject constructor() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  enum class Cue {
    /** Mic is hot: quick rising two-tone. */
    LISTENING,
    /** Endpoint acknowledged, before any thinking silence: single soft tick. */
    ACKNOWLEDGED,
    /** Conversation dismissed / stopped: short falling tone. */
    DISMISSED,
  }

  fun play(cue: Cue) {
    scope.launch {
      val pcm =
        when (cue) {
          Cue.LISTENING ->
            tone(660.0, 70) + tone(0.0, 20) + tone(990.0, 90)
          Cue.ACKNOWLEDGED -> tone(880.0, 60, gain = 0.35)
          Cue.DISMISSED -> tone(520.0, 70) + tone(0.0, 15) + tone(392.0, 100)
        }
      playPcm(pcm)
    }
  }

  private fun tone(freqHz: Double, durationMs: Int, gain: Double = 0.5): ShortArray {
    val n = SAMPLE_RATE * durationMs / 1000
    val out = ShortArray(n)
    if (freqHz <= 0.0) return out
    val attack = minOf(n / 4, SAMPLE_RATE * 8 / 1000)
    for (i in 0 until n) {
      // Sine with a linear attack/release envelope to avoid clicks.
      val env =
        when {
          i < attack -> i.toDouble() / attack
          i > n - attack -> (n - i).toDouble() / attack
          else -> 1.0
        }
      out[i] = (sin(2 * PI * freqHz * i / SAMPLE_RATE) * env * gain * Short.MAX_VALUE).toInt().toShort()
    }
    return out
  }

  private operator fun ShortArray.plus(other: ShortArray): ShortArray {
    val out = ShortArray(size + other.size)
    copyInto(out)
    other.copyInto(out, size)
    return out
  }

  private fun playPcm(pcm: ShortArray) {
    if (pcm.isEmpty()) return
    val track =
      AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        )
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(pcm.size * 2)
        .build()
    try {
      track.write(pcm, 0, pcm.size)
      track.play()
      // MODE_STATIC keeps playing after write; wait out the clip then release.
      Thread.sleep(pcm.size * 1000L / SAMPLE_RATE + 40)
    } catch (_: InterruptedException) {} finally {
      runCatching { track.release() }
    }
  }
}
