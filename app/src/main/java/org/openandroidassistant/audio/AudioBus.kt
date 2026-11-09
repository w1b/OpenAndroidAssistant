/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "AudioBus"

const val SAMPLE_RATE = 16_000
/** 20 ms frames: 320 samples. */
const val FRAME_SAMPLES = SAMPLE_RATE / 50

/**
 * The one shared microphone pipeline. It captures with `VOICE_COMMUNICATION` (hardware echo
 * cancellation, so the mic can stay open while the assistant speaks) and fans frames out to
 * every consumer: endpointer, waveform, speech-to-text, turn recorder.
 *
 * Right after a wake word, the frames come from LibreWakeWord's handoff stream instead (the wake
 * prefix followed by live audio), until [detachHandoff].
 */
@Singleton
class AudioBus @Inject constructor() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val leases = AtomicInteger(0)
  private var captureJob: Job? = null
  @Volatile private var handoff: InputStream? = null

  private val _frames =
    MutableSharedFlow<ShortArray>(replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  /** 20 ms 16 kHz mono PCM frames while capture is running. */
  val frames: SharedFlow<ShortArray> = _frames

  /** Holds the microphone open until closed. */
  @Synchronized
  fun acquire(): AutoCloseable {
    if (leases.incrementAndGet() == 1) start()
    var closed = false
    return AutoCloseable {
      synchronized(this) {
        if (!closed) {
          closed = true
          if (leases.decrementAndGet() == 0) stop()
        }
      }
    }
  }

  /** Use [stream] (16 kHz mono PCM16) as the mic until [detachHandoff]. */
  @Synchronized
  fun attachHandoff(stream: InputStream) {
    handoff?.let { runCatching { it.close() } }
    handoff = stream
    if (leases.get() > 0) {
      stop()
      start()
    }
  }

  /** Releases the wake-word handoff; live capture continues on the app's own recorder. */
  @Synchronized
  fun detachHandoff() {
    val stream = handoff ?: return
    handoff = null
    runCatching { stream.close() }
    if (leases.get() > 0) {
      stop()
      start()
    }
  }

  private fun start() {
    val stream = handoff
    captureJob = scope.launch { if (stream != null) readHandoff(stream) else record() }
  }

  private fun stop() {
    captureJob?.cancel()
    captureJob = null
  }

  private suspend fun readHandoff(stream: InputStream) {
    val bytes = ByteArray(FRAME_SAMPLES * 2)
    try {
      while (kotlinx.coroutines.currentCoroutineContext().isActive) {
        var filled = 0
        while (filled < bytes.size) {
          val n = stream.read(bytes, filled, bytes.size - filled)
          if (n < 0) return
          filled += n
        }
        val frame = ShortArray(FRAME_SAMPLES) { i -> ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
        _frames.emit(frame)
      }
    } catch (e: Exception) {
      Log.i(TAG, "handoff stream ended: ${e.message}")
    }
  }

  /**
   * Call-style processing (echo cancelling, noise suppression). Debug builds can try others for
   * speech recognition accuracy: setprop debug.oaa.mic_source recognition|mic|communication.
   */
  private fun micSource(): Int {
    if (!org.openandroidassistant.BuildConfig.DEBUG) return MediaRecorder.AudioSource.VOICE_COMMUNICATION
    val prop =
      runCatching {
          @Suppress("PrivateApi")
          Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.oaa.mic_source") as String
        }
        .getOrDefault("")
    return when (prop) {
      "recognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
      "mic" -> MediaRecorder.AudioSource.MIC
      else -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
    }.also { Log.i(TAG, "mic source ${prop.ifEmpty { "communication" }}") }
  }

  @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before a conversation starts.
  private suspend fun record() {
    val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val record =
      AudioRecord(
        micSource(),
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minBuffer * 2, FRAME_SAMPLES * 8),
      )
    if (record.state != AudioRecord.STATE_INITIALIZED) {
      Log.e(TAG, "AudioRecord failed to initialize")
      record.release()
      return
    }
    try {
      record.startRecording()
      val buffer = ShortArray(FRAME_SAMPLES)
      while (kotlinx.coroutines.currentCoroutineContext().isActive) {
        var filled = 0
        while (filled < buffer.size) {
          val n = record.read(buffer, filled, buffer.size - filled)
          if (n <= 0) break
          filled += n
        }
        if (filled == buffer.size) _frames.emit(buffer.copyOf())
      }
    } finally {
      runCatching { record.stop() }
      record.release()
    }
  }

  /**
   * Listens until [stop] returns true. The mic starts only once this listener is subscribed, so
   * no frame is lost between starting capture and listening (a wake-word prefix arrives at once).
   */
  suspend fun listen(stop: suspend (ShortArray) -> Boolean) {
    var lease: AutoCloseable? = null
    try {
      frames.onSubscription { lease = acquire() }.first { stop(it) }
    } finally {
      lease?.close()
    }
  }
}

/** Root-mean-square level of a frame, 0..1, for the waveform and voice detection. */
fun ShortArray.rms(): Float {
  if (isEmpty()) return 0f
  var acc = 0.0
  for (s in this) acc += s.toDouble() * s
  return (kotlin.math.sqrt(acc / size) / Short.MAX_VALUE).toFloat()
}
