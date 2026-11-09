/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openandroidassistant.data.settings.SettingsRepository
import org.openandroidassistant.data.settings.effectiveKittenTtsVoice
import org.openandroidassistant.data.settings.effectiveTtsPlaybackSpeed
import org.openandroidassistant.data.settings.effectiveTtsProvider
import org.openandroidassistant.proto.AssistantSettings
import org.openandroidassistant.proto.TtsProviderType

private const val TAG = "TtsSpeaker"
private const val PERF_TAG = "OaaPerf"
private const val FADE_MS = 150

/**
 * Speech output: Android TTS or on-device KittenTTS produces PCM, then an
 * [AudioTrack] the host fully controls plays it. That makes interruption a fast *fade*, not a
 * hard cut, and keeps playback epoch-tagged. `USAGE_ASSISTANT` lets AEC identify device playback.
 */
@Singleton
class TtsSpeaker
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val settingsRepository: SettingsRepository,
  private val kittenTts: KittenTtsSynthesizer,
) {
  private var tts: TextToSpeech? = null
  private var initializedEngine: String? = null
  private var unavailableEngineKey: String? = null
  private val mutex = Mutex()
  private val speakMutex = Mutex()
  /** One synthesis at a time: the Android engine has a single progress listener. */
  private val synthMutex = Mutex()
  private val trackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  /** The track the last sentence played on, kept open briefly so the next one joins it. */
  private var openTrack: AudioTrack? = null
  private var releaseJob: Job? = null
  private val activeTrack = AtomicReference<AudioTrack?>(null)
  private val stopGeneration = AtomicLong(0)
  @Volatile private var fadeRequested = false

  /** True while anything is being spoken. */
  @Volatile
  var isSpeaking: Boolean = false
    private set

  /**
   * Speaks [text]; suspends until playback finishes or [fadeOut] is called. Returns true if
   * playback ran to completion, false if it was faded out (or failed).
   */
  suspend fun speak(
    text: String,
    onPlaybackProgress: ((Float?) -> Unit)? = null,
  ): Boolean {
    if (text.isBlank()) return true
    val generation = stopGeneration.get()
    val audio = prepare(text) ?: return false
    if (generation != stopGeneration.get()) return false
    return play(audio, onPlaybackProgress)
  }

  /**
   * Synthesizes [text] without playing it, trimmed of the engine's padding, so the next sentence
   * can be ready while this one plays.
   */
  suspend fun prepare(text: String): Pcm16Audio? =
    synthMutex.withLock {
      val startedAt = SystemClock.elapsedRealtime()
      val audio = synthesize(text, settingsRepository.current())?.trimmedForSpeech()
      Log.i(PERF_TAG, "tts_synth text_chars=${text.length} synthesis_ms=${SystemClock.elapsedRealtime() - startedAt} audio_ms=${audio?.durationMs}")
      audio
    }

  /** Plays audio from [prepare]; returns true if it ran to completion, false if faded out. */
  suspend fun play(audio: Pcm16Audio, onPlaybackProgress: ((Float?) -> Unit)? = null): Boolean {
    val generation = stopGeneration.get()
    return speakMutex.withLock {
      if (generation != stopGeneration.get()) return@withLock false
      isSpeaking = true
      fadeRequested = false
      try {
        val playbackStartedAt = SystemClock.elapsedRealtime()
        val pauseMs = (SENTENCE_PAUSE_MS / settingsRepository.current().effectiveTtsPlaybackSpeed).toInt()
        playPcm(audio, pauseMs, onPlaybackProgress).also { completed ->
          Log.i(PERF_TAG, "tts_play audio_ms=${audio.durationMs} playback_ms=${SystemClock.elapsedRealtime() - playbackStartedAt} completed=$completed")
        }
      } finally {
        onPlaybackProgress?.let { listener -> runCatching { listener(null) } }
        isSpeaking = false
      }
    }
  }

  /** Initializes the selected speech engine while the user/model is still working. */
  suspend fun prewarm() {
    val startedAt = SystemClock.elapsedRealtime()
    val settings = settingsRepository.current()
    val ready =
      if (settings.effectiveTtsProvider == TtsProviderType.TTS_PROVIDER_KITTEN_NANO) {
        kittenTts.prewarm()
      } else {
        ensureAndroidTts(settings) != null || kittenTts.prewarm()
      }
    Log.i(
      PERF_TAG,
      "tts_prewarm provider=${settings.effectiveTtsProvider.name} " +
        "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} ready=$ready",
    )
  }

  /** Fast-fades any in-flight playback (no hard-cut click). Safe from any thread. */
  fun fadeOut() {
    fadeRequested = true
    stopGeneration.incrementAndGet()
  }

  private suspend fun ensureAndroidTts(settings: AssistantSettings): TextToSpeech? =
    mutex.withLock {
      val wantEngine = settings.ttsEngine.ifEmpty { null }
      val wantEngineKey = wantEngine ?: "<system-default>"
      if (unavailableEngineKey == wantEngineKey) return null
      val existing = tts
      if (existing != null && initializedEngine == wantEngine) {
        configureAndroidTts(existing, settings)
        return existing
      }

      existing?.shutdown()
      val ready = CompletableDeferred<Boolean>()
      var created: TextToSpeech? = null
      created =
        TextToSpeech(
          context,
          { status -> ready.complete(status == TextToSpeech.SUCCESS) },
          wantEngine,
        )
      val ok = withTimeoutOrNull(3_000) { ready.await() } == true
      if (!ok) {
        Log.e(TAG, "TTS init failed (engine=$wantEngine)")
        created.shutdown()
        tts = null
        unavailableEngineKey = wantEngineKey
        return null
      }
      configureAndroidTts(created, settings)
      tts = created
      initializedEngine = wantEngine
      unavailableEngineKey = null
      created
    }

  private fun configureAndroidTts(engine: TextToSpeech, settings: AssistantSettings) {
    val locale =
      if (settings.effectiveTtsProvider == TtsProviderType.TTS_PROVIDER_KITTEN_NANO) {
        Locale.US // English fallback while the Kitten model is unavailable.
      } else {
        settings.ttsLanguage.takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag)
          ?: Locale.getDefault()
      }
    engine.language = locale
    engine.setSpeechRate(settings.effectiveTtsPlaybackSpeed)
  }

  private suspend fun synthesize(text: String, settings: AssistantSettings): Pcm16Audio? {
    if (settings.effectiveTtsProvider == TtsProviderType.TTS_PROVIDER_KITTEN_NANO) {
      kittenTts.synthesize(
          text = text,
          voice = settings.effectiveKittenTtsVoice,
          speed = settings.effectiveTtsPlaybackSpeed,
        )
        ?.let { return it }
    } else {
      synthesizeWithAndroid(text, settings)?.let {
        // A working Android engine does not need the optional neural model retained.
        kittenTts.releaseIfLoaded()
        return it
      }
      // GrapheneOS and other privacy-focused builds may intentionally have no system TTS
      // service. If the optional local model is installed, stay fully functional rather than
      // leaving a completed response silent.
      Log.i(TAG, "Android TTS unavailable; falling back to KittenTTS")
      kittenTts
        .synthesize(
          text = text,
          voice = settings.effectiveKittenTtsVoice,
          speed = settings.effectiveTtsPlaybackSpeed,
        )
        ?.let { return it }
    }
    return if (settings.effectiveTtsProvider == TtsProviderType.TTS_PROVIDER_KITTEN_NANO) {
      synthesizeWithAndroid(text, settings)
    } else {
      null
    }
  }

  private suspend fun synthesizeWithAndroid(
    text: String,
    settings: AssistantSettings,
  ): Pcm16Audio? {
    val engine = ensureAndroidTts(settings) ?: return null
    val outFile = File.createTempFile("tts", ".wav", context.cacheDir)
    val utteranceId = "oaa-${System.nanoTime()}"
    val file = suspendCancellableCoroutine { continuation ->
      engine.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(id: String?) {}

          override fun onDone(id: String?) {
            if (id == utteranceId && continuation.isActive) continuation.resume(outFile)
          }

          @Deprecated("Deprecated in Java")
          override fun onError(id: String?) {
            if (id == utteranceId && continuation.isActive) continuation.resume(null)
          }

          override fun onError(id: String?, errorCode: Int) {
            if (id == utteranceId && continuation.isActive) continuation.resume(null)
          }
        }
      )
      val result = engine.synthesizeToFile(text, null, outFile, utteranceId)
      if (result != TextToSpeech.SUCCESS && continuation.isActive) continuation.resume(null)
      continuation.invokeOnCancellation { outFile.delete() }
    }
    return file?.let {
      try {
        parseWav(it.readBytes())
      } finally {
        it.delete()
      }
    }
  }

  /**
   * Plays PCM; returns true when it completed, false when faded out. A sentence that follows
   * within [TRACK_LINGER_MS] continues on the same track after [pauseMs] of silence, so there's
   * no track start-up between sentences.
   */
  private suspend fun playPcm(
    audio: Pcm16Audio,
    pauseMs: Int,
    onPlaybackProgress: ((Float?) -> Unit)?,
  ): Boolean =
    withContext(Dispatchers.IO) {
      releaseJob?.cancel()
      val bytes = audio.bytes
      val audibleWindow = onPlaybackProgress?.let { audio.audibleFrameWindow() }
      val reused = openTrack?.takeIf { it.sampleRate == audio.sampleRate && it.channelCount == audio.channels }
      if (reused == null) releaseTrack()
      val track = reused ?: createTrack(audio).also { openTrack = it }
      activeTrack.set(track)
      var completed = false
      try {
        if (reused == null) track.play()
        val base = Integer.toUnsignedLong(track.playbackHeadPosition)
        val pauseFrames = if (reused != null) audio.sampleRate * pauseMs / 1000 else 0
        if (pauseFrames > 0) {
          val silence = ByteArray(pauseFrames * 2 * audio.channels)
          track.write(silence, 0, silence.size)
        }
        onPlaybackProgress?.let { listener -> runCatching { listener(null) } }
        val chunk = 4096
        var offset = audio.dataOffset
        while (offset < bytes.size) {
          if (fadeRequested) {
            fade(track)
            return@withContext false
          }
          val len = minOf(chunk, bytes.size - offset)
          val written = track.write(bytes, offset, len)
          if (written <= 0) break
          offset += written
          if (onPlaybackProgress != null && audibleWindow != null) {
            val playedFrames = Integer.toUnsignedLong(track.playbackHeadPosition) - base - pauseFrames
            runCatching { onPlaybackProgress(audibleWindow.progressAt(playedFrames)) }
          }
        }
        // Drain the tail.
        val frames = (bytes.size - audio.dataOffset) / (2 * audio.channels) + pauseFrames
        val tailMs = 1000L * frames / audio.sampleRate
        val deadline = System.currentTimeMillis() + tailMs + 250
        while (System.currentTimeMillis() < deadline) {
          if (fadeRequested) {
            fade(track)
            return@withContext false
          }
          val playedFrames = Integer.toUnsignedLong(track.playbackHeadPosition) - base
          if (onPlaybackProgress != null && audibleWindow != null) {
            runCatching { onPlaybackProgress(audibleWindow.progressAt(playedFrames - pauseFrames)) }
          }
          if (playedFrames >= frames) break
          Thread.sleep(20)
        }
        completed = true
        completed
      } finally {
        onPlaybackProgress?.let { listener -> runCatching { listener(null) } }
        activeTrack.set(null)
        if (completed) {
          releaseJob = trackScope.launch {
            delay(TRACK_LINGER_MS)
            withContext(NonCancellable) { speakMutex.withLock { releaseTrack() } }
          }
        } else {
          releaseTrack()
        }
      }
    }

  private fun createTrack(audio: Pcm16Audio): AudioTrack {
    val mask = if (audio.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
    return AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANT)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(audio.sampleRate)
          .setChannelMask(mask)
          .build()
      )
      .setTransferMode(AudioTrack.MODE_STREAM)
      .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(audio.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT), 8192))
      .build()
  }

  private fun releaseTrack() {
    val track = openTrack ?: return
    openTrack = null
    runCatching {
      track.stop()
      track.release()
    }
  }

  private fun fade(track: AudioTrack) {
    val steps = 8
    for (i in steps downTo 0) {
      track.setVolume(i.toFloat() / steps)
      Thread.sleep((FADE_MS / steps).toLong())
    }
    runCatching { track.pause() }
  }

  /** Minimal RIFF parse: find fmt + data chunks. */
  private fun parseWav(bytes: ByteArray): Pcm16Audio? {
    if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF") return null
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var offset = 12
    var sampleRate = 0
    var channels = 1
    while (offset + 8 <= bytes.size) {
      val id = String(bytes, offset, 4)
      val size = buf.getInt(offset + 4)
      when (id) {
        "fmt " -> {
          channels = buf.getShort(offset + 10).toInt()
          sampleRate = buf.getInt(offset + 12)
        }
        "data" ->
          return if (sampleRate > 0) {
            Pcm16Audio(bytes, sampleRate, channels, offset + 8)
          } else null
      }
      offset += 8 + size + (size and 1)
    }
    return null
  }

  private companion object {
    /** The pause between sentences at 1× speed. */
    const val SENTENCE_PAUSE_MS = 280
    const val TRACK_LINGER_MS = 1_500L
  }
}

private val Pcm16Audio.durationMs: Long
  get() =
    if (sampleRate <= 0 || channels <= 0) 0
    else (bytes.size - dataOffset).toLong() * 1000 / (sampleRate * channels * 2)
