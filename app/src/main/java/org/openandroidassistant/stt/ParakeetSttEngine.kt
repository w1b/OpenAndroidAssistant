/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.stt

import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Live text is refreshed at most this often; longer recordings wait longer between passes. */
private const val MIN_PREVIEW_INTERVAL_MS = 300L
/**
 * This far into a pause, the audio so far is decoded as the likely final transcript, so it's ready
 * when the turn ends (800 ms of silence) instead of starting then.
 */
private const val FINAL_AFTER_SILENCE_SAMPLES = 16000 * 300 / 1000

/**
 * Speech-to-text with NVIDIA Parakeet TDT 0.6B v3 (int8) through sherpa-onnx.
 *
 * Parakeet decodes a whole recording at once, not frame by frame. It is fast enough on a phone
 * (about 15x realtime on four threads, Pixel 8a) to re-decode everything heard so far for the live
 * text, and to decode the finished turn in about a third of a second. It was far more accurate
 * than the streaming NeMo model on real requests (bench/stt).
 */
class ParakeetSttEngine(private val modelDir: File) : SttEngine {
  private val mutex = Mutex()
  @Volatile private var recognizer: OfflineRecognizer? = null
  /** Every decode runs here, one at a time: previews never pile up and never overlap the final pass. */
  private val decoder = Executors.newSingleThreadExecutor { r -> Thread(r, "parakeet-decode") }

  override suspend fun ensureLoaded() {
    mutex.withLock {
      if (recognizer != null) return
      withContext(Dispatchers.IO) {
        recognizer =
          OfflineRecognizer(
            null,
            OfflineRecognizerConfig(
              featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
              modelConfig =
                OfflineModelConfig(
                  transducer =
                    OfflineTransducerModelConfig(
                      encoder = File(modelDir, "encoder.int8.onnx").path,
                      decoder = File(modelDir, "decoder.int8.onnx").path,
                      joiner = File(modelDir, "joiner.int8.onnx").path,
                    ),
                  tokens = File(modelDir, "tokens.txt").path,
                  modelType = "nemo_transducer",
                  numThreads = 4,
                ),
            ),
          )
      }
    }
  }

  override suspend fun unload() {
    mutex.withLock {
      val r = recognizer ?: return
      recognizer = null
      // After any decode already queued.
      decoder.submit { r.release() }
    }
  }

  /** Returns at once: audio is buffered until the model is ready. */
  override suspend fun openStream(): SttStream = ParakeetStream(this)

  override suspend fun transcribe(pcm: ShortArray): String {
    ensureLoaded()
    val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
    return withContext(Dispatchers.IO) { decoder.submit<String> { decode(samples, samples.size) }.get() }
  }

  internal val ready: Boolean
    get() = recognizer != null

  /** Decodes the first [length] samples; on the decode thread only. */
  internal fun decode(samples: FloatArray, length: Int): String {
    val r = recognizer ?: return ""
    val stream = r.createStream()
    try {
      stream.acceptWaveform(if (length == samples.size) samples else samples.copyOf(length), 16000)
      r.decode(stream)
      return r.getResult(stream).text.trim()
    } finally {
      stream.release()
    }
  }

  internal fun submit(task: () -> Unit) = decoder.submit(task)

  internal fun <T> submitAndWait(task: () -> T): T = decoder.submit<T> { task() }.get()

  internal fun load() = runBlocking { ensureLoaded() }
}

private class ParakeetStream(private val engine: ParakeetSttEngine) : SttStream {
  private var samples = FloatArray(16000 * 8)
  @Volatile private var length = 0
  @Volatile private var preview = ""
  private val passRunning = AtomicBoolean(false)
  @Volatile private var closed = false
  private var lastPassStartedAt = 0L
  @Volatile private var lastPassMs = 0L
  /** Samples up to the end of the last frame with speech. */
  private var speechEnd = 0
  /** A decode taken in a pause: the [speechEnd] it covered, and its text once done. */
  private var candidateFor = -1
  @Volatile private var candidate: Pair<Int, String>? = null

  override fun accept(frame: ShortArray, speech: Boolean): String {
    synchronized(this) {
      if (length + frame.size > samples.size) samples = samples.copyOf(maxOf(samples.size * 2, length + frame.size))
      for (s in frame) samples[length++] = s / 32768f
      if (speech) speechEnd = length
    }
    if (!engine.ready) return preview
    val now = SystemClock.elapsedRealtime()
    val pause = speechEnd > 0 && length - speechEnd >= FINAL_AFTER_SILENCE_SAMPLES
    val wantCandidate = pause && candidateFor != speechEnd
    // A pass costs more as the recording grows; space them so decoding takes at most half the time.
    val wantPreview = now - lastPassStartedAt >= maxOf(MIN_PREVIEW_INTERVAL_MS, 2 * lastPassMs)
    if ((wantCandidate || wantPreview) && passRunning.compareAndSet(false, true)) {
      lastPassStartedAt = now
      val (buffer, n) = synchronized(this) { samples to length }
      val covers = if (wantCandidate) speechEnd.also { candidateFor = it } else -1
      engine.submit {
        try {
          if (!closed) {
            val started = SystemClock.elapsedRealtime()
            val text = engine.decode(buffer, n)
            lastPassMs = SystemClock.elapsedRealtime() - started
            if (!closed) preview = text
            if (covers >= 0) candidate = covers to text
          }
        } finally {
          passRunning.set(false)
        }
      }
    }
    return preview
  }

  override fun peek(): String {
    engine.load()
    val (buffer, n) = synchronized(this) { samples to length }
    return engine.submitAndWait { engine.decode(buffer, n) }.also { preview = it }
  }

  override fun finish(): String {
    // A turn can end before the model finished loading (a cold start): wait for it here.
    engine.load()
    val (buffer, n, end) = synchronized(this) { Triple(samples, length, speechEnd) }
    return engine.submitAndWait {
      // Everything since the pause decode was silence: that decode is the transcript.
      candidate?.takeIf { it.first == end }?.second ?: engine.decode(buffer, n)
    }.also { preview = it }
  }

  override fun close() {
    closed = true
  }
}
