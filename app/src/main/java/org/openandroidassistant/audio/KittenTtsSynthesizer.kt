/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.audio

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openandroidassistant.data.model.ArtifactStore
import org.openandroidassistant.data.model.Artifacts

private const val TAG = "KittenTts"

/** The friendly KittenTTS v0.8 voice names and their speaker IDs in the official model. */
val KITTEN_TTS_VOICES =
  linkedMapOf(
    "Bella" to 1,
    "Jasper" to 0,
    "Luna" to 3,
    "Bruno" to 2,
    "Rosie" to 5,
    "Hugo" to 4,
    "Kiki" to 7,
    "Leo" to 6,
  )

/** Lazy, reusable in-process KittenTTS Nano v0.8 inference through sherpa-onnx. */
@Singleton
class KittenTtsSynthesizer @Inject constructor(private val artifactStore: ArtifactStore) {
  private val mutex = Mutex()
  private var engine: OfflineTts? = null

  suspend fun synthesize(text: String, voice: String, speed: Float): Pcm16Audio? =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        if (!artifactStore.isInstalled(Artifacts.KITTEN_TTS_NANO_EN)) {
          Log.i(TAG, "KittenTTS model is not installed; using Android TTS")
          return@withLock null
        }

        runCatching {
            val tts = engine ?: createEngine().also { engine = it }
            val speakerId = KITTEN_TTS_VOICES[voice] ?: KITTEN_TTS_VOICES.getValue("Hugo")
            val generated = tts.generate(text = text, sid = speakerId, speed = speed)
            if (generated.samples.isEmpty() || generated.sampleRate <= 0) {
              error("KittenTTS returned no audio")
            }
            generated.samples.toPcm16(generated.sampleRate)
          }
          .onFailure { error ->
            Log.e(TAG, "KittenTTS synthesis failed; using Android TTS", error)
            engine?.release()
            engine = null
          }
          .getOrNull()
      }
    }

  /** Loads and validates the model without generating audio so first-response latency is hidden. */
  suspend fun prewarm(): Boolean =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        if (!artifactStore.isInstalled(Artifacts.KITTEN_TTS_NANO_EN)) return@withLock false
        runCatching {
            if (engine == null) engine = createEngine()
            true
          }
          .onFailure { error ->
            Log.e(TAG, "KittenTTS prewarm failed", error)
            engine?.release()
            engine = null
          }
          .getOrDefault(false)
      }
    }

  suspend fun releaseIfLoaded() =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        engine?.release()
        engine = null
      }
    }

  private fun createEngine(): OfflineTts {
    val modelDir = artifactStore.installedPath(Artifacts.KITTEN_TTS_NANO_EN)
    val threads =
      systemProperty("debug.oaa.tts_threads")?.toIntOrNull()?.coerceIn(1, 8) ?: DEFAULT_THREADS
    Log.i(TAG, "Loading KittenTTS Nano from ${modelDir.absolutePath} with $threads threads")
    val config =
      OfflineTtsConfig(
        model =
          OfflineTtsModelConfig(
            kitten =
              OfflineTtsKittenModelConfig(
                model = modelDir.resolve("model.fp32.onnx").absolutePath,
                voices = modelDir.resolve("voices.bin").absolutePath,
                tokens = modelDir.resolve("tokens.txt").absolutePath,
                dataDir = modelDir.resolve("espeak-ng-data").absolutePath,
              ),
            numThreads = threads,
          ),
        maxNumSentences = 1,
        silenceScale = 0.2f,
      )
    return OfflineTts(config = config).also {
      check(it.numSpeakers() == KITTEN_TTS_VOICES.size) {
        "Expected ${KITTEN_TTS_VOICES.size} KittenTTS voices, found ${it.numSpeakers()}"
      }
    }
  }

  private fun systemProperty(name: String): String? =
    try {
      @Suppress("PrivateApi")
      val clazz = Class.forName("android.os.SystemProperties")
      clazz.getMethod("get", String::class.java).invoke(null, name) as? String
    } catch (e: Exception) {
      null
    }?.takeIf { it.isNotEmpty() }

  private companion object {
    const val DEFAULT_THREADS = 4
  }

  private fun FloatArray.toPcm16(sampleRate: Int): Pcm16Audio {
    val pcm = ByteArray(size * 2)
    forEachIndexed { index, sample ->
      val value = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
      pcm[index * 2] = (value and 0xff).toByte()
      pcm[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
    }
    return Pcm16Audio(bytes = pcm, sampleRate = sampleRate)
  }
}
