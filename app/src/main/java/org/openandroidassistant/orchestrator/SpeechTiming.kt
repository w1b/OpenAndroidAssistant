/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.orchestrator

/** An end-exclusive range in the assistant's original, displayed speech text. */
internal data class SpeechTextRange(val start: Int, val endExclusive: Int)

/** One displayed token's approximate share of the synthesized utterance. */
internal data class ProportionalSpeechCue(
  val textRange: SpeechTextRange,
  val startFraction: Float,
  val endFraction: Float,
)

private data class WeightedToken(val range: SpeechTextRange, val weight: Double)

/**
 * Builds provider-independent karaoke cues. Timing is weighted by the token's normalized,
 * speakable length, with extra weight for digits and punctuation pauses. This keeps expansions
 * such as "$5" ("5 dollars") more reveal time while preserving positions in the original text.
 */
internal fun proportionalSpeechCues(
  displayText: String,
  normalizeToken: (String) -> String = { it },
): List<ProportionalSpeechCue> {
  val tokens =
    Regex("\\S+")
      .findAll(displayText)
      .mapNotNull { match ->
        val normalized = normalizeToken(match.value)
        val spokenWeight = estimateSpokenWeight(normalized)
        if (spokenWeight == 0.0) {
          null
        } else {
          WeightedToken(
            range = SpeechTextRange(match.range.first, match.range.last + 1),
            weight = spokenWeight + punctuationPauseWeight(match.value),
          )
        }
      }
      .toList()
  val totalWeight = tokens.sumOf { it.weight }
  if (totalWeight <= 0.0) return emptyList()

  var elapsedWeight = 0.0
  return tokens.mapIndexed { index, token ->
    val start = elapsedWeight / totalWeight
    elapsedWeight += token.weight
    ProportionalSpeechCue(
      textRange = token.range,
      startFraction = start.toFloat(),
      endFraction =
        if (index == tokens.lastIndex) 1f else (elapsedWeight / totalWeight).toFloat(),
    )
  }
}

/**
 * Maps audible playback progress to a continuous position in the original displayed text.
 *
 * The result is a 0..1 text fraction rather than a word index, so the renderer can reveal speech
 * through each token without discrete jumps while retaining the normalized timing weights.
 */
internal fun speechRevealProgress(
  cues: List<ProportionalSpeechCue>,
  progress: Float,
  displayTextLength: Int,
): Float? {
  if (!progress.isFinite() || progress < 0f || progress > 1f || displayTextLength <= 0) return null
  if (progress == 1f) return 1f
  val cue =
    cues.firstOrNull { progress >= it.startFraction && progress < it.endFraction }
      ?: return null
  val cueDuration = cue.endFraction - cue.startFraction
  val withinCue =
    if (cueDuration > 0f) (progress - cue.startFraction) / cueDuration else 1f
  val textPosition =
    cue.textRange.start +
      (cue.textRange.endExclusive - cue.textRange.start) * withinCue.coerceIn(0f, 1f)
  return (textPosition / displayTextLength).coerceIn(0f, 1f)
}

private fun estimateSpokenWeight(text: String): Double {
  var weight = 0.0
  text.forEach { character ->
    weight +=
      when {
        character.isLetter() -> 1.0
        character.isDigit() -> DIGIT_SPOKEN_LENGTH.getValue(character).toDouble()
        else -> 0.0
      }
  }
  return if (text.isBlank()) 0.0 else weight.coerceAtLeast(1.0)
}

private fun punctuationPauseWeight(token: String): Double {
  val end = token.lastOrNull() ?: return 0.0
  return when (end) {
    '.', '!', '?' -> 4.0
    ',', ';', ':', '—', '–' -> 2.0
    else -> 0.0
  }
}

private val DIGIT_SPOKEN_LENGTH =
  mapOf(
    '0' to 4,
    '1' to 3,
    '2' to 3,
    '3' to 5,
    '4' to 4,
    '5' to 4,
    '6' to 3,
    '7' to 5,
    '8' to 5,
    '9' to 4,
  )
