/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug builds: times an offline speech recognizer on the phone, on the WAV clips in
 * files/benchmarks/stt/clips, or the folder in the `clips` extra (16 kHz mono PCM16), with the
 * app's own sherpa-onnx.
 *
 * adb shell am broadcast -n …/…DebugReceiver --es stt_bench qwen3:<model dir> --ei threads 4
 * kinds: qwen3 (Qwen3-ASR), transducer (Parakeet TDT). Logs tag SttBench.
 */
object SttBench {
  private const val TAG = "SttBench"

  /** [clips]: a folder under files/ of 16 kHz mono WAVs. */
  fun run(context: Context, spec: String, threads: Int, clips: String) {
    val (kind, path) = spec.split(":", limit = 2)
    val dir = File(path)
    val model =
      when (kind) {
        "qwen3" ->
          OfflineModelConfig(
            qwen3Asr =
              OfflineQwen3AsrModelConfig(
                convFrontend = File(dir, "conv_frontend.onnx").path,
                encoder = File(dir, "encoder.int8.onnx").path,
                decoder = File(dir, "decoder.int8.onnx").path,
                tokenizer = File(dir, "tokenizer").path,
              ),
            numThreads = threads,
          )
        "transducer" ->
          OfflineModelConfig(
            transducer =
              OfflineTransducerModelConfig(
                encoder = File(dir, "encoder.int8.onnx").path,
                decoder = File(dir, "decoder.int8.onnx").path,
                joiner = File(dir, "joiner.int8.onnx").path,
              ),
            tokens = File(dir, "tokens.txt").path,
            modelType = "nemo_transducer",
            numThreads = threads,
          )
        else -> error("unknown kind $kind")
      }
    val loadStart = SystemClock.elapsedRealtime()
    val recognizer =
      OfflineRecognizer(
        null,
        OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = 16000, featureDim = if (kind == "qwen3") 128 else 80), modelConfig = model),
      )
    Log.i(TAG, "loaded $kind threads=$threads in ${SystemClock.elapsedRealtime() - loadStart} ms")
    var audioMs = 0L
    var decodeMs = 0L
    val files = File(context.filesDir, clips).listFiles { f -> f.name.endsWith(".wav") }.orEmpty().sortedBy { it.nameWithoutExtension.toIntOrNull() ?: 0 }
    for (clip in files) {
      val samples = readWav(clip)
      val start = SystemClock.elapsedRealtime()
      val stream = recognizer.createStream()
      stream.acceptWaveform(samples, 16000)
      recognizer.decode(stream)
      val text = recognizer.getResult(stream).text.trim()
      stream.release()
      val ms = SystemClock.elapsedRealtime() - start
      val clipMs = samples.size * 1000L / 16000
      audioMs += clipMs
      decodeMs += ms
      Log.i(TAG, "clip=${clip.nameWithoutExtension} audio_ms=$clipMs decode_ms=$ms text=$text")
    }
    recognizer.release()
    Log.i(TAG, "done $kind threads=$threads audio_ms=$audioMs decode_ms=$decodeMs rtfx=${"%.2f".format(audioMs.toDouble() / decodeMs.coerceAtLeast(1))}")
  }

  /** The samples of a 16 kHz mono PCM16 WAV, as floats; the data chunk is found by name. */
  private fun readWav(file: File): FloatArray {
    val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    var pos = 12
    while (pos + 8 <= b.limit()) {
      val id = String(ByteArray(4) { b.get(pos + it) })
      val size = b.getInt(pos + 4)
      if (id == "data") {
        val n = minOf(size, b.limit() - pos - 8) / 2
        return FloatArray(n) { b.getShort(pos + 8 + it * 2) / 32768f }
      }
      pos += 8 + size
    }
    return FloatArray(0)
  }
}
