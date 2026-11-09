/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.audio

import kotlin.math.abs

/** The portion of a PCM buffer that contains audible speech, expressed in sample frames. */
internal data class AudibleFrameWindow(
  val startFrame: Int,
  val endFrameExclusive: Int,
  val totalFrames: Int,
) {
  val durationFrames: Int
    get() = (endFrameExclusive - startFrame).coerceAtLeast(0)

  /** Playback progress within the audible window, or null while playback is in surrounding silence. */
  fun progressAt(playedFrames: Long): Float? {
    if (durationFrames == 0 || playedFrames < startFrame || playedFrames >= endFrameExclusive) {
      return null
    }
    return ((playedFrames - startFrame).toDouble() / durationFrames).toFloat().coerceIn(0f, 1f)
  }
}

/**
 * Finds leading and trailing silence so proportional highlighting starts with audible speech
 * rather than with encoder or engine padding. A small pad avoids clipping quiet consonant edges.
 */
internal fun Pcm16Audio.audibleFrameWindow(
  amplitudeThreshold: Int = 256,
  edgePaddingMs: Int = 20,
): AudibleFrameWindow {
  if (sampleRate <= 0 || channels <= 0 || dataOffset !in 0..bytes.size) {
    return AudibleFrameWindow(0, 0, 0)
  }
  val bytesPerFrame = channels * 2
  val totalFrames = (bytes.size - dataOffset) / bytesPerFrame
  if (totalFrames == 0) return AudibleFrameWindow(0, 0, 0)

  fun isAudible(frame: Int): Boolean {
    val frameOffset = dataOffset + frame * bytesPerFrame
    for (channel in 0 until channels) {
      val offset = frameOffset + channel * 2
      val sample =
        ((bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8))
          .toShort()
          .toInt()
      if (abs(sample) >= amplitudeThreshold) return true
    }
    return false
  }

  var first = 0
  while (first < totalFrames && !isAudible(first)) first++
  if (first == totalFrames) {
    // A silent or extremely quiet result still gets a stable full-buffer timeline.
    return AudibleFrameWindow(0, totalFrames, totalFrames)
  }

  var last = totalFrames - 1
  while (last > first && !isAudible(last)) last--

  val paddingFrames = (sampleRate.toLong() * edgePaddingMs / 1000).toInt()
  return AudibleFrameWindow(
    startFrame = (first - paddingFrames).coerceAtLeast(0),
    endFrameExclusive = (last + 1 + paddingFrames).coerceAtMost(totalFrames),
    totalFrames = totalFrames,
  )
}

/**
 * Cuts what the engine puts around speech so sentences join at a pause the app controls.
 * KittenTTS starts most clips with ~0.25 s of padding, including a faint breath (about −45 dBFS)
 * followed by a dip just before the first word. The cut goes to the quietest point within
 * [lookBackMs] of the first loud frame, so a soft onset such as "s" or "h" is kept. The end keeps
 * the decay until it falls below the quiet level.
 */
internal fun Pcm16Audio.trimmedForSpeech(
  loudDbfs: Double = -35.0,
  quietDbfs: Double = -60.0,
  lookBackMs: Int = 150,
  decayMs: Int = 200,
): Pcm16Audio {
  if (sampleRate <= 0 || channels <= 0 || dataOffset !in 0..bytes.size) return this
  val bytesPerFrame = channels * 2
  val totalFrames = (bytes.size - dataOffset) / bytesPerFrame
  val window = sampleRate / 100 // 10 ms
  if (window == 0 || totalFrames < window * 4) return this
  val windows = totalFrames / window
  val db =
    DoubleArray(windows) { w ->
      var sum = 0.0
      for (frame in w * window until (w + 1) * window) {
        val offset = dataOffset + frame * bytesPerFrame
        val s = ((bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)).toShort().toDouble()
        sum += s * s
      }
      10 * kotlin.math.log10(sum / window / (32768.0 * 32768.0) + 1e-12)
    }
  val onset = db.indexOfFirst { it > loudDbfs }
  if (onset < 0) return this
  val last = db.indexOfLast { it > loudDbfs }
  val back = lookBackMs / 10
  val start = (onset - 1 downTo maxOf(0, onset - back)).firstOrNull { db[it] < quietDbfs } ?: maxOf(0, onset - back)
  var end = last + 1
  val decayEnd = minOf(windows, last + 1 + decayMs / 10)
  while (end < decayEnd && db[end] >= quietDbfs) end++
  val startByte = dataOffset + start * window * bytesPerFrame
  val endByte = if (end >= windows) bytes.size else dataOffset + end * window * bytesPerFrame
  if (startByte == dataOffset && endByte == bytes.size) return this
  return Pcm16Audio(bytes.copyOfRange(startByte, endByte), sampleRate, channels)
}
