/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.orchestrator

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openandroidassistant.audio.Pcm16Audio
import org.openandroidassistant.audio.SpeechNormalizer
import org.openandroidassistant.audio.TtsSpeaker

/**
 * Speaks text in order: the model's text as it streams in (sentence by sentence), fixed progress
 * phrases, and approval prompts. [onReveal] reports how much of a part's text has been spoken,
 * so the reply lights up in sync with speech.
 */
class SpeechQueue(
  scope: CoroutineScope,
  private val tts: TtsSpeaker,
  private val normalizer: SpeechNormalizer,
  private val locale: () -> String,
  private val highlight: () -> Boolean,
  private val onReveal: (partId: Long, revealedChars: Int?) -> Unit,
) {
  private class Utterance(val partId: Long, val text: String, val offset: Int, val done: CompletableDeferred<Unit>?, val epoch: Long)

  private class Prepared(val u: Utterance, val audio: Pcm16Audio?)

  private val queue = Channel<Utterance>(Channel.UNLIMITED)
  /** Rendezvous: the next sentence is synthesized while the current one plays, no further ahead. */
  private val ready = Channel<Prepared>(Channel.RENDEZVOUS)
  private val pending = MutableStateFlow(0)
  /** Bumped by [stop]; anything queued before it is skipped. */
  private val epoch = AtomicLong(0)
  private val synthesizer: Job
  private val worker: Job

  init {
    synthesizer =
      scope.launch {
        for (u in queue) {
          val audio = if (u.epoch == epoch.get()) tts.prepare(normalizer.normalize(u.text, locale())) else null
          ready.send(Prepared(u, audio))
        }
      }
    worker =
      scope.launch {
        for (p in ready) {
          try {
            if (p.u.epoch == epoch.get() && p.audio != null) {
              lastStarted = p.u.text
              play(p.u, p.audio)
            }
          } finally {
            p.u.done?.complete(Unit)
            pending.update { it - 1 }
          }
        }
      }
  }

  val isIdle: Boolean
    get() = pending.value == 0

  /** The text that last started playing; approval listening uses it to know what could echo. */
  @Volatile
  var lastStarted: String? = null
    private set

  /** Queues [text], which starts at [offset] in the part [partId] shows. */
  fun enqueue(partId: Long, text: String, offset: Int = 0) {
    if (text.isBlank()) return
    pending.update { it + 1 }
    queue.trySend(Utterance(partId, text, offset, null, epoch.get()))
  }

  /** Queues [text] and suspends until it has been spoken (or skipped by [stop]). */
  suspend fun speakAndWait(partId: Long, text: String) {
    if (text.isBlank()) return
    val done = CompletableDeferred<Unit>()
    pending.update { it + 1 }
    queue.trySend(Utterance(partId, text, 0, done, epoch.get()))
    done.await()
  }

  suspend fun awaitIdle() {
    pending.first { it == 0 }
  }

  /** Fades out what's playing and drops everything queued. */
  fun stop() {
    epoch.incrementAndGet()
    while (true) {
      val u = queue.tryReceive().getOrNull() ?: break
      u.done?.complete(Unit)
      pending.update { it - 1 }
    }
    tts.fadeOut()
  }

  fun close() {
    stop()
    queue.close()
    ready.close()
    synthesizer.cancel()
    worker.cancel()
  }

  private suspend fun play(u: Utterance, audio: Pcm16Audio) {
    if (!highlight()) {
      tts.play(audio)
      onReveal(u.partId, null)
      return
    }
    val cues = proportionalSpeechCues(u.text) { normalizer.normalize(it, locale()) }
    var last = -1
    onReveal(u.partId, u.offset)
    tts.play(audio) { progress ->
      val fraction = progress?.let { speechRevealProgress(cues, it, u.text.length) } ?: return@play
      val chars = u.offset + (fraction * u.text.length).toInt()
      if (chars != last) {
        last = chars
        onReveal(u.partId, chars)
      }
    }
    onReveal(u.partId, u.offset + u.text.length)
  }
}

/**
 * Splits streaming text into speakable sentences. [take] returns the sentences completed since
 * the last call; [rest] returns whatever remains once the message is final.
 */
class SentenceChunker {
  private var consumed = 0

  /** Completed sentences in [text] past what was already taken, with their start offset. */
  fun take(text: String): List<Pair<Int, String>> {
    val out = mutableListOf<Pair<Int, String>>()
    var start = consumed
    var i = consumed
    while (i < text.length) {
      val c = text[i]
      val end = c == '.' || c == '!' || c == '?' || c == '\n'
      if (end && i + 1 < text.length && text[i + 1].isWhitespace()) {
        val sentence = text.substring(start, i + 1)
        // Very short fragments ("Dr.", "1.") join the next sentence.
        if (sentence.trim().length >= 12 || c == '\n') {
          out += start to sentence
          start = i + 1
        }
      }
      i++
    }
    consumed = start
    return out
  }

  fun rest(text: String): Pair<Int, String>? {
    if (consumed >= text.length) return null
    val r = consumed to text.substring(consumed)
    consumed = text.length
    return r.takeIf { it.second.isNotBlank() }
  }
}
