/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.orchestrator

/**
 * The strict yes/no rule for voice approval. A wrong approval sends the wrong
 * thing and a wrong decline only costs a retry, so it's deliberately lopsided:
 * - approve only on a clean affirmative followed by [silenceToApproveMs] of silence;
 * - decline when the user pauses and their words aren't the start of an affirmative.
 *
 * Decided at a pause, not mid-word: the live transcript re-decodes the audio so far, and half a
 * word comes out as something else ("See you." for the start of "send it"). By [pauseToDeclineMs]
 * into a pause the whole answer has been decoded.
 */
class ApprovalGrammar(private val silenceToApproveMs: Long = 600, private val pauseToDeclineMs: Long = 500) {

  enum class Verdict {
    UNDECIDED,
    APPROVE,
    DECLINE,
  }

  /**
   * Feeds the transcript so far and how long it has been silent since the last speech.
   * Once it returns APPROVE or DECLINE, the decision is final.
   */
  fun update(transcript: String, silenceMs: Long): Verdict {
    val words = normalize(transcript).let { w ->
      // Repeating the last word, not finished yet ("yes ye"): the repeat.
      if (w.size >= 2 && w[w.size - 2].startsWith(w.last())) w.dropLast(1) else w
    }
    if (words.isEmpty()) return Verdict.UNDECIDED
    // The live text is re-decoded while the user talks, so its last word can be half a word
    // ("sen" for "send"): it only has to be the start of the affirmative's word.
    if (AFFIRMATIVES.none { a -> a.size >= words.size && a.subList(0, words.size - 1) == words.dropLast(1) && a[words.size - 1].startsWith(words.last()) }) {
      return if (silenceMs >= pauseToDeclineMs) Verdict.DECLINE else Verdict.UNDECIDED
    }
    val complete = AFFIRMATIVES.any { it == words }
    return if (complete && silenceMs >= silenceToApproveMs) Verdict.APPROVE else Verdict.UNDECIDED
  }

  companion object {
    private val AFFIRMATIVES: List<List<String>> =
      listOf(
          "yes",
          "yeah",
          "yep",
          "yup",
          "sure",
          "okay",
          "ok",
          "do it",
          "send it",
          "go ahead",
          "yes please",
          "yes send it",
          "yes do it",
          "call",
          "call them",
          "confirm",
        )
        .map { it.split(' ') }

    /** The whole of [text] is one of the affirmatives. */
    fun isAffirmative(text: String): Boolean = normalize(text) in AFFIRMATIVES

    /**
     * Sounds that aren't an answer. The recognizer writes faint noise, and the prompt's leftover
     * echo, as "uh" or "mm"; on their own they decided a decline.
     */
    private val FILLERS = setOf("a", "ah", "eh", "er", "ha", "hm", "hmm", "huh", "mm", "mmm", "oh", "uh", "uhm", "um", "umm")

    /** Lowercase words without fillers; a word said twice in a row counts once ("yes, yes"). */
    internal fun normalize(text: String): List<String> =
      text.lowercase().replace(Regex("[^a-z' ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() && it !in FILLERS }
        .let { words -> words.filterIndexed { i, w -> i == 0 || w != words[i - 1] } }
  }
}
