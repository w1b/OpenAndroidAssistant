/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.audio

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad as SherpaVad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.math.max

/** Decides whether each 20 ms frame is speech. */
interface SpeechDetector : AutoCloseable {
  fun isSpeech(frame: ShortArray): Boolean

  override fun close() {}
}

/**
 * Silero VAD (through sherpa-onnx). Energy thresholds fail on real phone recordings: quiet words
 * read as silence and a turn ends mid-sentence. Silero judges each 32 ms window by whether it
 * sounds like speech, with hysteresis so a soft syllable doesn't flip it.
 */
class SileroSpeechDetector(assets: AssetManager) : SpeechDetector {
  private val vad =
    SherpaVad(
      assets,
      VadModelConfig(
        sileroVadModelConfig =
          SileroVadModelConfig(model = "silero_vad.onnx", threshold = 0.5f, minSilenceDuration = 0.1f, minSpeechDuration = 0.1f, windowSize = WINDOW),
        sampleRate = SAMPLE_RATE,
        numThreads = 1,
        provider = "cpu",
      ),
    )
  private val pending = FloatArray(WINDOW)
  private var filled = 0
  private var speaking = false

  override fun isSpeech(frame: ShortArray): Boolean {
    for (s in frame) {
      pending[filled++] = s / 32768f
      if (filled == WINDOW) {
        val p = vad.compute(pending.copyOf())
        speaking = if (speaking) p > 0.35f else p > 0.5f
        filled = 0
      }
    }
    return speaking
  }

  override fun close() = vad.release()

  private companion object {
    const val WINDOW = 512
  }
}

/** Energy-based fallback with an adaptive noise floor, for tests and when Silero can't load. */
class EnergySpeechDetector(private val ratio: Float = 2.5f, private val absoluteFloor: Float = 0.008f) : SpeechDetector {
  private var noiseFloor = 0.01f

  override fun isSpeech(frame: ShortArray): Boolean = isSpeech(frame.rms())

  fun isSpeech(frameRms: Float): Boolean {
    val speech = frameRms > max(noiseFloor * ratio, absoluteFloor)
    if (!speech) noiseFloor = 0.95f * noiseFloor + 0.05f * frameRms
    return speech
  }
}

/** Endpointing configuration for one capture. */
data class EndpointConfig(
  /** Trailing silence that ends the turn. */
  val silenceTimeoutMs: Long = 800,
  /** Give up if no speech starts within this window. */
  val noSpeechTimeoutMs: Long = 8000,
  /** Hard maximum turn length. */
  val maxTurnMs: Long = 60_000,
)

sealed interface EndpointEvent {
  data object SpeechStarted : EndpointEvent

  /** The turn is over: silence or maximum length. */
  data class Endpoint(val reason: EndReason) : EndpointEvent

  data object NoSpeech : EndpointEvent
}

enum class EndReason {
  SILENCE,
  MAX_LENGTH,
  NO_SPEECH,
}

/** Frame-driven endpointer. Feed 20 ms frames' speech decisions; at most one event per frame. */
class Endpointer(private val config: EndpointConfig) {
  private var elapsedMs = 0L
  private var speechSeen = false
  private var silenceMs = 0L

  fun onFrame(speech: Boolean, frameMs: Long = 20): EndpointEvent? {
    elapsedMs += frameMs
    if (!speechSeen) {
      if (speech) {
        speechSeen = true
        silenceMs = 0
        return EndpointEvent.SpeechStarted
      }
      return if (elapsedMs >= config.noSpeechTimeoutMs) EndpointEvent.NoSpeech else null
    }
    if (elapsedMs >= config.maxTurnMs) return EndpointEvent.Endpoint(EndReason.MAX_LENGTH)
    if (speech) {
      silenceMs = 0
      return null
    }
    silenceMs += frameMs
    return if (silenceMs >= config.silenceTimeoutMs) EndpointEvent.Endpoint(EndReason.SILENCE) else null
  }
}
