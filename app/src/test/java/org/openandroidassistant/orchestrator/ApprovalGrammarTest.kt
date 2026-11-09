/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openandroidassistant.orchestrator.ApprovalGrammar.Verdict

class ApprovalGrammarTest {
  /** Feeds (transcript, silenceMs) once every 20 ms and returns the first final verdict. */
  private fun decide(vararg steps: Pair<String, Long>): Verdict {
    val g = ApprovalGrammar()
    for ((text, silence) in steps) {
      val v = g.update(text, silence)
      if (v != Verdict.UNDECIDED) return v
    }
    return Verdict.UNDECIDED
  }

  @Test
  fun cleanYesThenSilenceApproves() {
    assertEquals(Verdict.APPROVE, decide("yes" to 0, "yes" to 300, "yes" to 620))
  }

  @Test
  fun yesNeedsTheSilence() {
    assertEquals(Verdict.UNDECIDED, decide("yes" to 0, "yes" to 400))
  }

  @Test
  fun yesButKeepsTalkingDeclines() {
    val steps = arrayOf("yes" to 0L, "yes but" to 0L) + Array(20) { "yes but say" to 0L }
    assertEquals(Verdict.UNDECIDED, decide(*steps))
    assertEquals(Verdict.DECLINE, decide(*(steps + ("yes but say twenty" to 520L))))
  }

  @Test
  fun noDeclinesAtThePause() {
    // Mid-word text can be wrong ("See you." for "send it"), so nothing declines until a pause.
    assertEquals(Verdict.UNDECIDED, decide(*Array(20) { "no" to 0L }))
    assertEquals(Verdict.DECLINE, decide("no" to 0, "no" to 520))
    assertEquals(Verdict.UNDECIDED, decide("See you." to 0, "Send it." to 0))
  }

  @Test
  fun aRevisedFirstGuessDoesNotDecline() {
    // The recognizer's first guess "the" becomes "yes" before it settles.
    val steps = arrayOf("the" to 0L, "the" to 0L, "yes" to 0L) + Array(40) { "yes" to 700L }
    assertEquals(Verdict.APPROVE, decide(*steps))
  }

  @Test
  fun multiWordAffirmatives() {
    assertEquals(Verdict.APPROVE, decide("send" to 0, "send it" to 0, "send it" to 700))
    assertEquals(Verdict.APPROVE, decide("go ahead" to 700))
  }

  @Test
  fun fillersAreNoAnswer() {
    // Faint noise and prompt echo came out as "Uh" or "Mm." and declined on their own.
    val g = ApprovalGrammar()
    assertEquals(ApprovalGrammar.Verdict.UNDECIDED, g.update("Uh", 0))
    assertEquals(ApprovalGrammar.Verdict.UNDECIDED, g.update("Mm.", 0))
    assertEquals(ApprovalGrammar.Verdict.APPROVE, g.update("Uh, yes", 700))
    assertEquals(ApprovalGrammar.Verdict.DECLINE, ApprovalGrammar().let { it.update("oh no", 0); it.update("oh no", 520) })
  }

  @Test
  fun halfAWordIsNotADecline() {
    // Parakeet re-decodes mid-word: "Sen" on the way to "Send it", "Yes, ye" on the way to "yes, yes".
    val send = ApprovalGrammar()
    assertEquals(ApprovalGrammar.Verdict.UNDECIDED, send.update("Sen", 0))
    assertEquals(ApprovalGrammar.Verdict.UNDECIDED, send.update("Sen", 0))
    assertEquals(ApprovalGrammar.Verdict.APPROVE, send.update("Send it.", 700))
    val repeat = ApprovalGrammar()
    assertEquals(ApprovalGrammar.Verdict.UNDECIDED, repeat.update("Yes, ye", 0))
    assertEquals(ApprovalGrammar.Verdict.UNDECIDED, repeat.update("Yes, ye", 0))
    assertEquals(ApprovalGrammar.Verdict.APPROVE, repeat.update("Yes, yes, yes.", 700))
    // A real non-affirmative still declines.
    val no = ApprovalGrammar()
    assertEquals(ApprovalGrammar.Verdict.UNDECIDED, no.update("No, make", 0))
    assertEquals(ApprovalGrammar.Verdict.DECLINE, no.update("No, make it 1pm.", 520))
  }
}
