/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.ui.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.log10
import kotlin.math.max
import kotlinx.coroutines.delay

private const val WAVEFORM_WINDOW_MS = 30_000L
private const val WAVEFORM_BAR_COUNT = 100
private const val WAVEFORM_FLOOR_DB = -50f
private const val WAVEFORM_CEILING_DB = -18f

/**
 * A 30-second live waveform for the user's speech (native audio in shows a waveform, never a live
 * transcript). It fills from left to right for the first 30 seconds, then scrolls so the newest
 * sample remains at the right edge.
 */
@Composable
fun Waveform(
  micLevel: Float,
  active: Boolean,
  color: Color,
  modifier: Modifier = Modifier,
  barCount: Int = WAVEFORM_BAR_COUNT,
) {
  val history = remember { mutableStateListOf<Float>() }
  val liveLevel = remember { Animatable(0f) }
  val peakLevel = remember { PeakLevel() }
  val displayLevel = waveformDisplayLevel(micLevel)
  val latestLevel by rememberUpdatedState(displayLevel)
  SideEffect { peakLevel.observe(displayLevel) }

  // The live head is decoupled from the 300 ms history buckets: loudness appears immediately,
  // then relaxes just slowly enough to remain legible instead of flickering between mic frames.
  LaunchedEffect(active, displayLevel) {
    when {
      !active -> liveLevel.snapTo(0f)
      displayLevel >= liveLevel.value -> liveLevel.snapTo(displayLevel)
      else ->
        liveLevel.animateTo(
          targetValue = displayLevel,
          animationSpec = tween(durationMillis = 110, easing = LinearOutSlowInEasing),
        )
    }
  }

  LaunchedEffect(active, barCount) {
    history.clear()
    peakLevel.reset()
    if (!active) return@LaunchedEffect
    val sampleMs = (WAVEFORM_WINDOW_MS / barCount.coerceAtLeast(1)).coerceAtLeast(1L)
    while (true) {
      delay(sampleMs)
      history.appendRolling(max(peakLevel.take(), latestLevel), barCount)
    }
  }

  Canvas(modifier = modifier) {
    if (barCount <= 0) return@Canvas
    val gap = size.width / barCount
    val barWidth = max(1f, gap * 0.48f)
    val midY = size.height / 2f

    // A quiet center track makes the 30-second window visible before it fills.
    repeat(barCount) { i ->
      val x = i * gap + gap / 2f
      drawLine(
        color = color.copy(alpha = 0.18f),
        start = Offset(x, midY - 1f),
        end = Offset(x, midY + 1f),
        strokeWidth = barWidth,
        cap = StrokeCap.Round,
      )
    }

    history.forEachIndexed { i, level ->
      val amplitude = max(0.06f, level)
      val half = amplitude * midY
      val x = i * gap + gap / 2f
      drawLine(
        color = color,
        start = Offset(x, midY - half),
        end = Offset(x, midY + half),
        strokeWidth = barWidth,
        cap = StrokeCap.Round,
      )
    }

    // Draw the current mic level before its interval is committed to history. While filling, it
    // advances left-to-right; once full, it stays pinned to the right edge as history scrolls.
    if (active) {
      val headIndex = minOf(history.size, barCount - 1)
      val amplitude = max(0.06f, liveLevel.value)
      val half = amplitude * midY
      val x = headIndex * gap + gap / 2f
      drawLine(
        color = color,
        start = Offset(x, midY - half),
        end = Offset(x, midY + half),
        strokeWidth = barWidth,
        cap = StrokeCap.Round,
      )
    }
  }
}

private class PeakLevel {
  private var value = 0f

  fun observe(level: Float) {
    value = max(value, level)
  }

  fun take(): Float = value.also { value = 0f }

  fun reset() {
    value = 0f
  }
}

internal fun MutableList<Float>.appendRolling(sample: Float, capacity: Int) {
  if (capacity <= 0) return
  if (size >= capacity) removeAt(0)
  add(sample.coerceIn(0f, 1f))
}

/**
 * Maps raw normalized RMS onto the range people expect from a voice meter. Raw speech RMS is
 * generally far below 1.0, so a linear mapping makes even loud speech look nearly flat.
 */
internal fun waveformDisplayLevel(rms: Float): Float {
  if (!rms.isFinite() || rms <= 0f) return 0f
  val db = 20f * log10(rms.coerceAtMost(1f))
  return ((db - WAVEFORM_FLOOR_DB) / (WAVEFORM_CEILING_DB - WAVEFORM_FLOOR_DB))
    .coerceIn(0f, 1f)
}
